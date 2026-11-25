package de.raindancer118.shelved.metadata;

import de.raindancer118.shelved.book.BookFormat;
import de.raindancer118.shelved.book.Isbn;
import de.raindancer118.shelved.book.Names;
import de.raindancer118.shelved.book.Titles;
import de.raindancer118.shelved.pricing.PriceParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Library catalogue behind an SRU interface with MARC21 records (the Deutsche Nationalbibliothek).
 * Free, no key needed. The profile says how to ask.
 */
public abstract class MarcSruClient {

    private static final Logger log = LoggerFactory.getLogger(MarcSruClient.class);
    private static final String MARC_NS = "http://www.loc.gov/MARC21/slim";
    private static final Pattern YEAR = Pattern.compile("(1[5-9]|20)\\d{2}");
    private static final Pattern PAGE_COUNT = Pattern.compile("(\\d+)(?=[^\\d]*?(?:,|S\\.|Seiten))");
    private static final Pattern HEIGHT_CM = Pattern.compile("(\\d+(?:[.,]\\d)?)\\s*cm");

    protected record Profile(String source, String baseUrl, String schema, String isbnIndex, String titleIndex,
                             String personIndex, String booksFilter, long minIntervalMs) {
    }

    // the DNB answers 429 when a re-check fires dozens of lookups in a row
    static final Profile DNB = new Profile("DNB", "https://services.dnb.de/sru/dnb", "MARC21-xml",
            "num", "tit", "per", " and mat=books", 700);
    private static final int ATTEMPTS = 4;

    private final RestClient http;
    private final Profile profile;
    private Duration backoff = Duration.ofSeconds(2);
    private long lastCall;

    protected MarcSruClient(RestClient http, Profile profile) {
        this.http = http;
        this.profile = profile;
    }

    /** First wait after a "too many requests"; doubles with every further attempt. */
    void backoff(Duration first) {
        this.backoff = first;
    }

    public Optional<BookMetadata> byIsbn(String isbn13) {
        return query(profile.isbnIndex() + "=" + isbn13, 5).stream()
                .filter(m -> isbn13.equals(m.isbn13()))
                .findFirst();
    }

    public List<BookMetadata> search(String title, String author) {
        // mat=books drops audio books and e-books on the DNB side, so older paperbacks make it into the page
        return query(titleQuery(title, author), 30);
    }

    /** Everything printed under this person, with the name as the catalogue writes it ("Maas, Sarah J."). */
    public List<BookMetadata> byAuthor(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String catalogName = Names.sortForm(name.trim()).replace("\"", "");
        return query(profile.personIndex() + "=\"" + catalogName + "\"" + profile.booksFilter(), 100);
    }

    private String titleQuery(String title, String author) {
        StringBuilder q = new StringBuilder(profile.titleIndex()).append('=').append(clean(title));
        String surname = surnameOf(author);
        if (!surname.isBlank()) {
            q.append(" and ").append(profile.personIndex()).append('=').append(clean(surname));
        }
        return q.append(profile.booksFilter()).toString();
    }

    private List<BookMetadata> query(String cql, int max) {
        byte[] body = fetch(cql, max);
        return body == null ? List.of() : parse(body, true, profile);
    }

    /**
     * @throws CatalogUnavailableException if the catalogue keeps refusing or answers with something that isn't SRU,
     *                                     so callers don't take an outage for "no such book"
     */
    private byte[] fetch(String cql, int max) {
        URI uri = UriComponentsBuilder.fromUriString(profile.baseUrl())
                .queryParam("version", "1.1")
                .queryParam("operation", "searchRetrieve")
                .queryParam("query", cql)
                .queryParam("recordSchema", profile.schema())
                .queryParam("maximumRecords", max)
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();
        String reason = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (attempt > 0) {
                sleep(backoff.multipliedBy(1L << (attempt - 1)).toMillis());
            }
            throttle();
            try {
                byte[] body = http.get().uri(uri).retrieve().body(byte[].class);
                if (body != null && new String(body, 0, Math.min(body.length, 400), StandardCharsets.UTF_8).contains("searchRetrieveResponse")) {
                    return body;
                }
                reason = "keine SRU-Antwort";
            } catch (HttpClientErrorException.TooManyRequests | HttpServerErrorException e) {
                reason = e.getStatusCode().toString();
                continue;
            } catch (RestClientException e) {
                reason = e.getMessage();
            }
            break; // errors other than rate limits and server trouble don't get better by asking again
        }
        log.warn("{} query failed: {}", profile.source(), reason);
        throw new CatalogUnavailableException(profile.source(), reason);
    }

    private synchronized void throttle() {
        long wait = lastCall + profile.minIntervalMs() - System.currentTimeMillis();
        if (wait > 0) {
            sleep(wait);
        }
        lastCall = System.currentTimeMillis();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CatalogUnavailableException("SRU", "unterbrochen");
        }
    }

    static List<BookMetadata> parse(byte[] xml) {
        return parse(xml, true, DNB);
    }

    static List<BookMetadata> parse(byte[] xml, boolean printedOnly) {
        return parse(xml, printedOnly, DNB);
    }

    static List<BookMetadata> parse(byte[] xml, boolean printedOnly, Profile profile) {
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
                toMetadata(rec, profile).ifPresent(out::add);
            }
            return out;
        } catch (Exception e) {
            log.warn("Could not parse {} answer: {}", profile.source(), e.getMessage());
            return List.of();
        }
    }

    private static Optional<BookMetadata> toMetadata(Marc rec, Profile profile) {
        String isbn = null;
        String priceNote = null;
        for (Element field : rec.fields("020")) {
            Optional<String> candidate = Isbn.toIsbn13(Marc.sub(field, "a"));
            if (candidate.isPresent() && isbn == null) {
                isbn = candidate.get();
                priceNote = Marc.sub(field, "c");
            }
        }
        String rawTitle = isbd(rec.first("245", "a"));
        if (rawTitle == null) {
            return Optional.empty();
        }
        String partTitle = rec.first("245", "p");
        String title = Titles.display(rawTitle);
        String subtitle = isbd(rec.first("245", "b"));
        String publisher = isbd(Optional.ofNullable(rec.first("264", "b")).orElse(rec.first("260", "b")));
        if (partTitle != null) {
            // multi-part work "Samurai ; 1, Der Weg des Kämpfers": the part is the book
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
            // only the library's own blurb: some records also list the publisher's home page as "Inhaltstext"
            String url = Marc.sub(f, "u");
            if (descriptionUrl == null && "Inhaltstext".equals(Marc.sub(f, "3")) && url != null
                    && (url.startsWith("https://services.dnb.de/") || url.startsWith("https://d-nb.info/") || url.startsWith("http://d-nb.info/"))) {
                descriptionUrl = url;
            }
        }

        return Optional.of(new BookMetadata(
                isbn,
                title,
                Titles.sortForm(rawTitle),
                subtitle == null ? null : Titles.display(subtitle),
                authors,
                sortNames.isEmpty() ? null : sortNames.getFirst(),
                publisher,
                year(Optional.ofNullable(rec.first("264", "c")).orElse(rec.first("260", "c"))),
                pages(extent),
                heightMm(dims),
                format,
                rec.first("041", "a"),
                PriceParser.germanEuroCents(priceNote).orElse(null),
                descriptionUrl,
                null,
                isbn == null || profile != DNB ? null : "https://portal.dnb.de/opac/mvb/cover?isbn=" + isbn,
                profile.source()));
    }

    private static void addIfAuthor(Element field, List<String> names) {
        String name = isbd(Marc.sub(field, "a"));
        String role = Marc.sub(field, "4");
        String relator = Marc.sub(field, "e"); // Library of Congress: "author." instead of a code
        boolean author = role == null ? relator == null || relator.startsWith("author") : "aut".equals(role);
        if (name != null && author && !names.contains(name)) {
            names.add(name);
        }
    }

    private static final Pattern ISBD_END = Pattern.compile("\\s*[/:;,=]\\s*$");

    /** Cataloguing punctuation at the end of a field ("The cruel prince /", "Black, Holly,"). */
    static String isbd(String s) {
        return s == null ? null : ISBD_END.matcher(s).replaceFirst("").trim();
    }

    static Integer year(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = YEAR.matcher(s);
        if (m.find()) {
            return Integer.parseInt(m.group());
        }
        // new titles sometimes carry only "26"
        Matcher two = TWO_DIGIT_YEAR.matcher(s);
        if (two.matches()) {
            int yy = Integer.parseInt(two.group(1));
            int century = yy <= java.time.Year.now().getValue() % 100 + 1 ? 2000 : 1900;
            return century + yy;
        }
        return null;
    }

    private static final Pattern TWO_DIGIT_YEAR = Pattern.compile("\\D*(\\d{2})\\D*");

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

    private static final Pattern CQL_KEYWORD = Pattern.compile("(?i)\\b(and|or|not|prox)\\b");

    private static String clean(String s) {
        // dashes, colons and quotes break CQL or narrow the match ("Mysteria – Das Tor des Feuers");
        // a bare "not" in a title is CQL's NOT ("This Kingdom Will Not Kill Me" found nothing), so keywords are quoted
        String words = s == null ? "" : Titles.joinBracketLetters(s).replaceAll("[\"()=<>/\\\\–—:;,.!?„“”'&-]", " ").replaceAll("\\s+", " ").trim();
        return CQL_KEYWORD.matcher(words).replaceAll("\"$1\"");
    }

    private static String surnameOf(String author) {
        if (author == null || author.isBlank()) {
            return "";
        }
        String sort = Names.sortForm(author);
        int comma = sort.indexOf(',');
        return comma > 0 ? sort.substring(0, comma) : sort;
    }

    record Marc(Element record) {

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
