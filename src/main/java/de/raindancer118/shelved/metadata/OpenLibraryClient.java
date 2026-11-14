package de.raindancer118.shelved.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.raindancer118.shelved.book.Isbn;
import de.raindancer118.shelved.book.Names;
import de.raindancer118.shelved.book.Titles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** openlibrary.org – good for English editions and covers. */
public class OpenLibraryClient {

    private static final Logger log = LoggerFactory.getLogger(OpenLibraryClient.class);

    private final RestClient http;

    public OpenLibraryClient(RestClient http) {
        this.http = http;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Named(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Cover(String small, String medium, String large) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Edition(String title, String subtitle, List<Named> authors, List<Named> publishers,
                   String publish_date, Integer number_of_pages, Cover cover) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchDoc(String title, List<String> author_name, Long cover_i, List<String> isbn,
                     Integer first_publish_year, Integer number_of_pages_median, List<String> publisher) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResult(List<SearchDoc> docs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EditionRecord(List<String> series, List<String> publishers) {
    }

    public record SeriesHit(String name, int volume) {
    }

    // "Discworld, Book 1", "The Folk of the Air ; 2", "Discworld #41", "Mistborn (3)", "Tintenwelt-Trilogie, Bd. 2"
    private static final java.util.regex.Pattern SERIES = java.util.regex.Pattern.compile(
            "^(.+?)\\s*[,;:#(\\-–]*\\s*(?:(?:book|bk|band|bd|vol|volume|tome|teil|nr|no)\\.?\\s*)?#?(?<!\\d)(\\d{1,3})\\)?\\s*$",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** The free-text series field of an edition. Only numbered entries count, and no publisher imprints. */
    public Optional<SeriesHit> seriesByIsbn(String isbn13) {
        try {
            EditionRecord e = http.get().uri(URI.create("https://openlibrary.org/isbn/" + isbn13 + ".json"))
                    .retrieve().body(EditionRecord.class);
            if (e == null || e.series() == null) {
                return Optional.empty();
            }
            String publisher = e.publishers() == null || e.publishers().isEmpty() ? null : e.publishers().getFirst();
            return e.series().stream().map(OpenLibraryClient::parseSeries).flatMap(Optional::stream)
                    .filter(h -> !MarcSruClient.Marc.imprint(h.name(), publisher))
                    .findFirst();
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound ex) {
            return Optional.empty(); // Open Library doesn't know every edition
        } catch (RestClientException ex) {
            log.warn("Open Library edition lookup failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    static Optional<SeriesHit> parseSeries(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        java.util.regex.Matcher m = SERIES.matcher(raw.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        Integer volume = MarcSruClient.volumeNumber(m.group(2));
        String name = MarcSruClient.cleanSeries(m.group(1));
        if (volume == null || name.isBlank() || MarcSruClient.notALiterarySeries(name)) {
            return Optional.empty();
        }
        return Optional.of(new SeriesHit(name, volume));
    }

    public Optional<BookMetadata> byIsbn(String isbn13) {
        URI uri = URI.create("https://openlibrary.org/api/books?bibkeys=ISBN:" + isbn13 + "&format=json&jscmd=data");
        try {
            Map<String, Edition> body = http.get().uri(uri).retrieve().body(new ParameterizedTypeReference<>() {
            });
            if (body == null || body.isEmpty()) {
                return Optional.empty();
            }
            Edition e = body.values().iterator().next();
            List<String> authors = e.authors() == null ? List.of() : e.authors().stream().map(Named::name).toList();
            return Optional.of(new BookMetadata(
                    isbn13, e.title(), Titles.sortForm(e.title()), e.subtitle(), authors,
                    authors.isEmpty() ? null : Names.sortForm(authors.getFirst()),
                    e.publishers() == null || e.publishers().isEmpty() ? null : e.publishers().getFirst().name(),
                    MarcSruClient.year(e.publish_date()), e.number_of_pages(), null, null, null, null, null, null,
                    e.cover() == null ? null : e.cover().large(), "OPENLIBRARY"));
        } catch (RestClientException ex) {
            log.warn("Open Library lookup failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public List<BookMetadata> search(String title, String author) {
        URI uri = UriComponentsBuilder.fromUriString("https://openlibrary.org/search.json")
                .queryParam("title", title)
                .queryParamIfPresent("author", Optional.ofNullable(author).filter(a -> !a.isBlank()))
                .queryParam("fields", "title,author_name,cover_i,isbn,first_publish_year,number_of_pages_median,publisher")
                .queryParam("limit", 5)
                .encode(StandardCharsets.UTF_8)
                .build().toUri();
        try {
            SearchResult r = http.get().uri(uri).retrieve().body(SearchResult.class);
            if (r == null || r.docs() == null) {
                return List.of();
            }
            return r.docs().stream().map(OpenLibraryClient::fromDoc).toList();
        } catch (RestClientException ex) {
            log.warn("Open Library search failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private static BookMetadata fromDoc(SearchDoc d) {
        List<String> authors = d.author_name() == null ? List.of() : d.author_name();
        String isbn = d.isbn() == null ? null : d.isbn().stream()
                .map(Isbn::toIsbn13).flatMap(Optional::stream).findFirst().orElse(null);
        return new BookMetadata(
                isbn, d.title(), Titles.sortForm(d.title()), null, authors,
                authors.isEmpty() ? null : Names.sortForm(authors.getFirst()),
                d.publisher() == null || d.publisher().isEmpty() ? null : d.publisher().getFirst(),
                d.first_publish_year(), d.number_of_pages_median(), null, null, null, null, null, null,
                d.cover_i() == null ? null : "https://covers.openlibrary.org/b/id/" + d.cover_i() + "-L.jpg",
                "OPENLIBRARY");
    }
}
