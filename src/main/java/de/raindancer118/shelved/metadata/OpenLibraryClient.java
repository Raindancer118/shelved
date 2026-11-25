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

    // ------------------------------------------------------------------ authors

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthorDoc(String key, String name, String birth_date, String top_work, Integer work_count) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthorDocs(List<AuthorDoc> docs) {
    }

    /** Open Library writes a bio either as plain text or as {type, value}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthorRecord(String name, String birth_date, String death_date, tools.jackson.databind.JsonNode bio, List<Long> photos) {
    }

    public record AuthorHit(String key, String name, String birthDate, String topWork, int works) {
    }

    public record AuthorDetail(String key, String name, String birthDate, String deathDate, String bio, boolean hasPhoto) {
    }

    /** Authors by name. The search is fuzzy, callers decide which hits really are the person. */
    public List<AuthorHit> searchAuthors(String query) {
        URI uri = UriComponentsBuilder.fromUriString("https://openlibrary.org/search/authors.json")
                .queryParam("q", query).queryParam("limit", 10).build().encode(StandardCharsets.UTF_8).toUri();
        try {
            AuthorDocs r = http.get().uri(uri).retrieve().body(AuthorDocs.class);
            return r == null || r.docs() == null ? List.of() : r.docs().stream()
                    .filter(d -> d.key() != null && d.name() != null)
                    .map(d -> new AuthorHit(d.key(), d.name(), d.birth_date(), d.top_work(), d.work_count() == null ? 0 : d.work_count()))
                    .toList();
        } catch (RestClientException e) {
            log.debug("Open Library author search failed: {}", e.getMessage());
            return List.of();
        }
    }

    public Optional<AuthorDetail> author(String key) {
        if (key == null || !key.matches("OL\\d+A")) {
            return Optional.empty();
        }
        try {
            AuthorRecord a = http.get().uri(URI.create("https://openlibrary.org/authors/" + key + ".json")).retrieve().body(AuthorRecord.class);
            if (a == null) {
                return Optional.empty();
            }
            String bio = a.bio() == null ? null : a.bio().isString() ? a.bio().asString() : a.bio().path("value").asString(null);
            return Optional.of(new AuthorDetail(key, a.name(), a.birth_date(), a.death_date(), bio, a.photos() != null && !a.photos().isEmpty()));
        } catch (RestClientException e) {
            log.debug("Open Library author {} failed: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WorkRef(String key) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EditionWorks(List<WorkRef> works) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RatingSummary(Double average, Integer count) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ratings(RatingSummary summary) {
    }

    /** Ratings belong to the work; the edition tells which work it is. */
    public Optional<Rating> rating(String isbn13) {
        try {
            EditionWorks e = http.get().uri(URI.create("https://openlibrary.org/isbn/" + isbn13 + ".json")).retrieve().body(EditionWorks.class);
            String work = e == null || e.works() == null || e.works().isEmpty() ? null : e.works().getFirst().key();
            if (work == null || !work.matches("/works/OL\\d+W")) {
                return Optional.empty();
            }
            Ratings r = http.get().uri(URI.create("https://openlibrary.org" + work + "/ratings.json")).retrieve().body(Ratings.class);
            RatingSummary sum = r == null ? null : r.summary();
            return sum == null || sum.average() == null || sum.count() == null || sum.count() == 0 ? Optional.empty()
                    : Optional.of(new Rating("Open Library", sum.average(), sum.count()));
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WorkDoc(String title, Double ratings_average, Integer ratings_count) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WorkDocs(List<WorkDoc> docs) {
    }

    /** Ratings of the work behind any edition, found by title and author (for editions Open Library doesn't list). */
    public Optional<Rating> workRating(String title, String author) {
        if (title == null || title.isBlank()) {
            return Optional.empty();
        }
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString("https://openlibrary.org/search.json")
                .queryParam("title", title).queryParam("fields", "title,ratings_average,ratings_count").queryParam("limit", 5);
        if (author != null && !author.isBlank()) {
            b.queryParam("author", author);
        }
        try {
            WorkDocs r = http.get().uri(b.build().encode(StandardCharsets.UTF_8).toUri()).retrieve().body(WorkDocs.class);
            return r == null || r.docs() == null ? Optional.empty() : r.docs().stream()
                    .filter(d -> d.ratings_count() != null && d.ratings_count() > 0 && d.ratings_average() != null)
                    .filter(d -> Similarity.title(title, d.title()) >= 0.8)
                    .findFirst()
                    .map(d -> new Rating("Open Library", d.ratings_average(), d.ratings_count()));
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }
}
