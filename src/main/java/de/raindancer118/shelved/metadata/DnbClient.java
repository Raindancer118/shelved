package de.raindancer118.shelved.metadata;

import de.raindancer118.shelved.book.BookFormat;
import de.raindancer118.shelved.book.Isbn;
import de.raindancer118.shelved.book.Names;
import de.raindancer118.shelved.book.Titles;
import de.raindancer118.shelved.pricing.PriceParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deutsche Nationalbibliothek, SRU interface with MARC21-xml records. Free, no key needed. */
public class DnbClient {

    private static final Logger log = LoggerFactory.getLogger(DnbClient.class);
    private static final String MARC_NS = "http://www.loc.gov/MARC21/slim";
    private static final Pattern YEAR = Pattern.compile("(1[5-9]|20)\\d{2}");
    private static final Pattern PAGE_COUNT = Pattern.compile("(\\d+)(?=[^\\d]*?(?:,|S\\.|Seiten))");
    private static final Pattern HEIGHT_CM = Pattern.compile("(\\d+(?:[.,]\\d)?)\\s*cm");

    private final RestClient http;

    public DnbClient(RestClient http) {
        this.http = http;
    }

    public Optional<BookMetadata> byIsbn(String isbn13) {
        return query("num=" + isbn13, 5).stream()
                .filter(m -> isbn13.equals(m.isbn13()))
                .findFirst();
    }

    public List<BookMetadata> search(String title, String author) {
        StringBuilder q = new StringBuilder("tit=").append(clean(title));
        String surname = surnameOf(author);
        if (!surname.isBlank()) {
            q.append(" and per=").append(clean(surname));
        }
        // mat=books drops audio books and e-books on the DNB side, so older paperbacks make it into the page
        return query(q.append(" and mat=books").toString(), 30);
    }

    /**
     * All volumes of a series as the national library knows them (German titles, publisher numbering).
     * Most frequent title per volume number wins, so one odd edition doesn't rename a volume.
     */
    public List<de.raindancer118.shelved.series.SeriesCatalogVolume> seriesCatalog(String seriesName, String author) {
        StringBuilder q = new StringBuilder("tit=").append(clean(seriesName));
        String surname = surnameOf(author);
        if (!surname.isBlank()) {
            q.append(" and per=").append(clean(surname));
        }
        byte[] body = fetch(q.append(" and mat=books").toString(), 100);
        java.util.Map<Integer, java.util.Map<String, Integer>> titles = new java.util.TreeMap<>();
        for (BookMetadata m : body == null ? List.<BookMetadata>of() : parse(body, false)) {
            if (m.seriesName() != null && m.seriesVolume() != null
                    && Similarity.title(seriesName, m.seriesName()) >= 0.6) {
                titles.computeIfAbsent(m.seriesVolume(), k -> new java.util.LinkedHashMap<>()).merge(m.title(), 1, Integer::sum);
            }
        }
        return titles.entrySet().stream()
                .map(e -> new de.raindancer118.shelved.series.SeriesCatalogVolume(e.getKey(),
                        e.getValue().entrySet().stream().max(java.util.Map.Entry.comparingByValue()).orElseThrow().getKey()))
                .toList();
    }

    private List<BookMetadata> query(String cql, int max) {
        byte[] body = fetch(cql, max);
        return body == null ? List.of() : parse(body);
    }

    private byte[] fetch(String cql, int max) {
        URI uri = UriComponentsBuilder.fromUriString("https://services.dnb.de/sru/dnb")
                .queryParam("version", "1.1")
                .queryParam("operation", "searchRetrieve")
                .queryParam("query", cql)
                .queryParam("recordSchema", "MARC21-xml")
                .queryParam("maximumRecords", max)
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();
        try {
            return http.get().uri(uri).retrieve().body(byte[].class);
        } catch (RestClientException e) {
            log.warn("DNB query failed: {}", e.getMessage());
            return null;
        }
    }

    static List<BookMetadata> parse(byte[] xml) {
        return parse(xml, true);
    }

    static List<BookMetadata> parse(byte[] xml, boolean printedOnly) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            NodeList records = doc.getElementsByTagNameNS(MARC_NS, "record");
            List<BookMetadata> out = new ArrayList<>();
            for (int i = 0; i < records.getLength(); i++) {
                Marc rec = new Marc((Element) records.item(i));
                if (printedOnly && !rec.isPrintedBook()) {
                    continue;
                }
                toMetadata(rec).ifPresent(out::add);
            }
            return out;
        } catch (Exception e) {
            log.warn("Could not parse DNB answer: {}", e.getMessage());
            return List.of();
        }
    }

    private static Optional<BookMetadata> toMetadata(Marc rec) {
        String isbn = null;
        String priceNote = null;
        for (Element field : rec.fields("020")) {
            Optional<String> candidate = Isbn.toIsbn13(Marc.sub(field, "a"));
            if (candidate.isPresent() && isbn == null) {
                isbn = candidate.get();
                priceNote = Marc.sub(field, "c");
            }
        }
        String rawTitle = rec.first("245", "a");
        if (rawTitle == null) {
            return Optional.empty();
        }
        String part = rec.first("245", "n");
        String partTitle = rec.first("245", "p");
        String title = Titles.display(rawTitle);
        String subtitle = rec.first("245", "b");
        Series series = rec.series(Optional.ofNullable(rec.first("264", "b")).orElse(rec.first("260", "b")));
        if (partTitle != null) {
            // multi-part work "Samurai ; 1, Der Weg des Kämpfers": the part is the book, the main title its series
            if (series == null && volumeNumber(part) != null) {
                series = new Series(cleanSeries(rawTitle), volumeNumber(part));
            }
            rawTitle = partTitle;
            title = Titles.display(partTitle);
        }

        List<String> sortNames = new ArrayList<>();
        for (Element f : rec.fields("100")) {
            addIfAuthor(f, sortNames);
        }
        for (Element f : rec.fields("700")) {
            if (Marc.sub(f, "t") == null) {
                addIfAuthor(f, sortNames);
            }
        }
        List<String> authors = sortNames.stream().map(Names::displayForm).toList();

        String extent = rec.first("300", "a");
        String dims = rec.first("300", "c");
        String note = priceNote;
        BookFormat format = rec.productForm().or(() -> PriceParser.format(note)).orElse(null);

        String descriptionUrl = null;
        for (Element f : rec.fields("856")) {
            if ("Inhaltstext".equals(Marc.sub(f, "3"))) {
                descriptionUrl = Marc.sub(f, "u");
            }
        }

        return Optional.of(new BookMetadata(
                isbn,
                title,
                Titles.sortForm(rawTitle),
                subtitle == null ? null : Titles.display(subtitle),
                authors,
                sortNames.isEmpty() ? null : sortNames.getFirst(),
                Optional.ofNullable(rec.first("264", "b")).orElse(rec.first("260", "b")),
                year(Optional.ofNullable(rec.first("264", "c")).orElse(rec.first("260", "c"))),
                pages(extent),
                heightMm(dims),
                format,
                rec.first("041", "a"),
                PriceParser.germanEuroCents(priceNote).orElse(null),
                descriptionUrl,
                null,
                isbn == null ? null : "https://portal.dnb.de/opac/mvb/cover?isbn=" + isbn,
                "DNB",
                series == null ? null : series.name(),
                series == null ? null : series.volume()));
    }

    private static void addIfAuthor(Element field, List<String> names) {
        String name = Marc.sub(field, "a");
        String role = Marc.sub(field, "4");
        if (name != null && (role == null || "aut".equals(role)) && !names.contains(name)) {
            names.add(name);
        }
    }

    record Series(String name, Integer volume) {
    }

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    /** "8. Band", "Band 1", "Buch 1", "1" → number; imprint numbers ("8016 : Piper Fantasy") are too big to be volumes. */
    static Integer volumeNumber(String v) {
        if (v == null) {
            return null;
        }
        Matcher m = NUMBER.matcher(v);
        if (!m.find()) {
            return null;
        }
        int n = Integer.parseInt(m.group());
        return n >= 1 && n <= 300 ? n : null;
    }

    // "Der ... Band der Tintenwelt-Reihe", "... Roman des Zyklus von der Scheibenwelt", "Bd. ... der Mysteria-Trilogie"
    private static final Pattern ORDINAL_PLACEHOLDER = Pattern.compile(
            "^.*?\\.\\.\\.\\s*(?:\\S+\\s+)??(?:des\\s+Zyklus\\s+)?(?:von\\s+)?(?:der|des|dem|die|den)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_A_SERIES = Pattern.compile(
            "(?i)\\bwerke?\\b|werkausgabe|bibliothek|klassiker|edition|taschenbuch|bücherei|sammlung|gesamtausgabe|sonderausgabe|leichter sprache");

    static String cleanSeries(String name) {
        String n = Titles.display(name).replaceAll("\\s*/\\s*[^/]+$", "").trim(); // "Mysteria-Trilogie / Peter Freund"
        n = ORDINAL_PLACEHOLDER.matcher(n).replaceFirst("");
        return WikidataClient.cleanSeriesName(n.replaceAll("\\s*[;:,.]\\s*$", "").trim());
    }

    /** Collected works, imprints and anthologies number their books too, but they are no reading series. */
    static boolean notALiterarySeries(String name) {
        return NOT_A_SERIES.matcher(Titles.display(name)).find();
    }

    static Integer year(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = YEAR.matcher(s);
        return m.find() ? Integer.parseInt(m.group()) : null;
    }

    /** "327, 286 Seiten" is an omnibus: both novels count. "XII, 300 S." counts 300. */
    static Integer pages(String extent) {
        if (extent == null || extent.contains("Online") || extent.contains("CD")) {
            return null;
        }
        int sum = 0;
        Matcher m = PAGE_COUNT.matcher(extent);
        while (m.find()) {
            sum += Integer.parseInt(m.group(1));
        }
        return sum > 0 ? sum : null;
    }

    static Integer heightMm(String dims) {
        if (dims == null) {
            return null;
        }
        Matcher m = HEIGHT_CM.matcher(dims);
        return m.find() ? (int) Math.round(Double.parseDouble(m.group(1).replace(',', '.')) * 10) : null;
    }

    private static String clean(String s) {
        // dashes, colons and quotes break CQL or narrow the match ("Mysteria – Das Tor des Feuers")
        return s == null ? "" : s.replaceAll("[\"()=<>/\\\\–—:;,.!?„“”'&]", " ").replaceAll("\\s-\\s", " ").replaceAll("\\s+", " ").trim();
    }

    private static String surnameOf(String author) {
        if (author == null || author.isBlank()) {
            return "";
        }
        String sort = Names.sortForm(author);
        int comma = sort.indexOf(',');
        return comma > 0 ? sort.substring(0, comma) : sort;
    }

    private record Marc(Element record) {

        List<Element> fields(String tag) {
            List<Element> out = new ArrayList<>();
            NodeList list = record.getElementsByTagNameNS(MARC_NS, "datafield");
            for (int i = 0; i < list.getLength(); i++) {
                Element e = (Element) list.item(i);
                if (tag.equals(e.getAttribute("tag"))) {
                    out.add(e);
                }
            }
            return out;
        }

        String first(String tag, String code) {
            for (Element f : fields(tag)) {
                String v = sub(f, code);
                if (v != null) {
                    return v;
                }
            }
            return null;
        }

        static String sub(Element field, String code) {
            NodeList subs = field.getElementsByTagNameNS(MARC_NS, "subfield");
            for (int i = 0; i < subs.getLength(); i++) {
                Element s = (Element) subs.item(i);
                if (code.equals(s.getAttribute("code"))) {
                    String text = s.getTextContent();
                    return text == null || text.isBlank() ? null : text.trim();
                }
            }
            return null;
        }

        boolean isPrintedBook() {
            String content = first("336", "b");
            if (content != null && !"txt".equals(content)) {
                return false; // "spw" = spoken word, i.e. audio book with a printed booklet
            }
            String carrier = first("338", "b");
            if (carrier != null) {
                return "nc".equals(carrier);
            }
            String extent = first("300", "a");
            return extent == null || !(extent.contains("Online") || extent.contains("CD"));
        }

        /**
         * The literary series: only 800 (author + series title). 490/830 turned out to be mostly publisher
         * imprints ("Piper Fantasy", "Goldmann ; 48682", "Bild-am-Sonntag-Fantasy-Bibliothek").
         */
        Series series(String publisher) {
            for (Element f : fields("800")) {
                String t = sub(f, "t");
                Integer v = volumeNumber(sub(f, "v"));
                if (t != null && v != null && !notALiterarySeries(t) && !imprint(t, publisher)) {
                    return new Series(cleanSeries(t), v);
                }
            }
            return null;
        }

        private static boolean imprint(String series, String publisher) {
            String s = Names.collationKey(Titles.display(series));
            if (publisher == null) {
                return false;
            }
            String p = Names.collationKey(Titles.display(publisher));
            String firstWord = p.split("[^\\p{L}]+")[0];
            return !firstWord.isBlank() && (s.equals(p) || s.startsWith(firstWord) || p.contains(s));
        }

        Optional<BookFormat> productForm() {
            for (Element f : fields("653")) {
                String v = sub(f, "a");
                if (v != null && v.startsWith("(Produktform)")) {
                    String p = v.toLowerCase();
                    if (p.contains("hardback")) {
                        return Optional.of(BookFormat.HARDCOVER);
                    }
                    if (p.contains("paperback")) {
                        return Optional.of(BookFormat.PAPERBACK);
                    }
                    return Optional.of(BookFormat.OTHER);
                }
            }
            return Optional.empty();
        }
    }
}
