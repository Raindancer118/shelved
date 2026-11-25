package de.raindancer118.shelved.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.raindancer118.shelved.book.Isbn;
import de.raindancer118.shelved.book.Names;
import de.raindancer118.shelved.book.Titles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Google Books: blurbs, covers and page counts for many editions the DNB has no cover for. */
public class GoogleBooksClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleBooksClient.class);
    private static final Map<String, String> LANG = Map.of("de", "ger", "en", "eng", "fr", "fre", "es", "spa", "it", "ita", "nl", "dut");

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Identifier(String type, String identifier) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ImageLinks(String thumbnail, String smallThumbnail) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VolumeInfo(String title, String subtitle, List<String> authors, String publisher, String publishedDate,
                      String description, List<Identifier> industryIdentifiers, Integer pageCount, String language,
                      ImageLinks imageLinks, Double averageRating, Integer ratingsCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Volume(VolumeInfo volumeInfo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Volumes(Integer totalItems, List<Volume> items) {
    }

    private final RestClient http;
    private final String apiKey;

    public GoogleBooksClient(RestClient http, String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Optional<BookMetadata> byIsbn(String isbn13) {
        return query("isbn:" + isbn13, 1).stream().findFirst();
    }

    public List<BookMetadata> search(String title, String author) {
        String q = "intitle:" + title.replaceAll("[–—:;\"„“”]", " ").replaceAll("\\s+", " ").trim() + (author == null || author.isBlank() ? "" : " inauthor:" + surname(author));
        return query(q, 10).stream()
                .filter(m -> m.publisher() == null || !m.publisher().toLowerCase().contains("ebook"))
                .toList();
    }

    /** Google's star average for an edition, when people rated it there. */
    public Optional<Rating> rating(String isbn13) {
        return volumes("isbn:" + isbn13, 1).stream().findFirst()
                .filter(v -> v.averageRating() != null && v.ratingsCount() != null && v.ratingsCount() > 0)
                .map(v -> new Rating("Google Books", v.averageRating(), v.ratingsCount()));
    }

    private List<BookMetadata> query(String q, int max) {
        return volumes(q, max).stream().filter(v -> v.title() != null).map(GoogleBooksClient::toMetadata).toList();
    }

    private List<VolumeInfo> volumes(String q, int max) {
        if (!enabled()) {
            return List.of();
        }
        URI uri = UriComponentsBuilder.fromUriString("https://www.googleapis.com/books/v1/volumes")
                .queryParam("q", "{q}")
                .queryParam("country", "DE")
                .queryParam("printType", "books")
                .queryParam("maxResults", max)
                .queryParam("key", "{key}")
                .encode(StandardCharsets.UTF_8)
                .buildAndExpand(q, apiKey).toUri();
        try {
            Volumes v = http.get().uri(uri).retrieve().body(Volumes.class);
            if (v == null || v.items() == null) {
                return List.of();
            }
            return v.items().stream().map(Volume::volumeInfo).filter(java.util.Objects::nonNull).toList();
        } catch (RestClientException e) {
            log.warn("Google Books failed: {}", e.getMessage().replace(apiKey, "***"));
            return List.of();
        }
    }

    private static BookMetadata toMetadata(VolumeInfo v) {
        String isbn = v.industryIdentifiers() == null ? null : v.industryIdentifiers().stream()
                .map(Identifier::identifier).map(Isbn::toIsbn13).flatMap(Optional::stream).findFirst().orElse(null);
        List<String> authors = v.authors() == null ? List.of() : v.authors();
        return new BookMetadata(isbn, v.title(), Titles.sortForm(v.title()), v.subtitle(), authors,
                authors.isEmpty() ? null : Names.sortForm(authors.getFirst()), v.publisher(),
                MarcSruClient.year(v.publishedDate()), v.pageCount() == null || v.pageCount() <= 0 ? null : v.pageCount(),
                null, null, v.language() == null ? null : LANG.getOrDefault(v.language(), v.language()), null, null,
                Texts.plain(v.description()), cover(v.imageLinks()), "GOOGLE");
    }

    static String cover(ImageLinks links) {
        if (links == null || links.thumbnail() == null) {
            return null;
        }
        // thumbnails are 128 px; "fife" asks Google's image server for a bigger rendition of the same scan
        return links.thumbnail().replace("http://", "https://").replace("&edge=curl", "") + "&fife=w600";
    }

    private static String surname(String author) {
        String sort = Names.sortForm(author);
        int comma = sort.indexOf(',');
        return comma > 0 ? sort.substring(0, comma) : sort;
    }
}
