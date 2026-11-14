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
        return query(q.toString(), 20);
    }

    private List<BookMetadata> query(String cql, int max) {
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
            byte[] body = http.get().uri(uri).retrieve().body(byte[].class);
            return body == null ? List.of() : parse(body);
        } catch (RestClientException e) {
            log.warn("DNB query failed: {}", e.getMessage());
            return List.of();
        }
    }

    static List<BookMetadata> parse(byte[] xml) {
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
                if (!rec.isPrintedBook()) {
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
        if (partTitle != null) {
            subtitle = (part != null ? part + ". " : "") + Titles.display(partTitle);
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
                "DNB"));
    }

    private static void addIfAuthor(Element field, List<String> names) {
        String name = Marc.sub(field, "a");
        String role = Marc.sub(field, "4");
        if (name != null && (role == null || "aut".equals(role)) && !names.contains(name)) {
            names.add(name);
        }
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
        return s == null ? "" : s.replaceAll("[\"()=<>/\\\\]", " ").replaceAll("\\s+", " ").trim();
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
            String carrier = first("338", "b");
            if (carrier != null) {
                return "nc".equals(carrier);
            }
            String extent = first("300", "a");
            return extent == null || !(extent.contains("Online") || extent.contains("CD"));
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
