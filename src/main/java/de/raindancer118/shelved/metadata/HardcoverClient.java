package de.raindancer118.shelved.metadata;

import de.raindancer118.shelved.book.Names;
import de.raindancer118.shelved.series.SeriesCatalogVolume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * hardcover.app, a reading community with carefully kept series and positions. Works are linked to their
 * editions in every language, so a German ISBN finds its series and the German titles of the other volumes.
 * Needs a personal token; the API allows 60 requests a minute.
 */
public class HardcoverClient {

    private static final Logger log = LoggerFactory.getLogger(HardcoverClient.class);
    private static final String URL = "https://api.hardcover.app/v1/graphql";

    private static final String EDITION = """
            query ($isbn: String!) { editions(where: {isbn_13: {_eq: $isbn}}, limit: 1) { language { language }
              book { title book_series(where: {position: {_is_null: false}}) { position featured
                series { id name primary_books_count author { name } } } } } }""";
    private static final String SERIES = """
            query ($id: Int!) { series_by_pk(id: $id) { name
              book_series(where: {position: {_is_null: false}}, order_by: {position: asc}) { position compilation
                book { title users_count editions(where: {language: {language: {_eq: "German"}}}, order_by: {users_count: desc}, limit: 1) { title } } } } }""";
    private static final String SEARCH = """
            query ($q: String!) { search(query: $q, query_type: "Series", per_page: 5) { results } }""";

    public record SeriesMatch(long seriesId, String name, int volume) {
    }

    private final RestClient http;
    private final String token;
    private final long minIntervalMs;
    private long lastCall;

    public HardcoverClient(RestClient http, String token) {
        this(http, token, 1100);
    }

    HardcoverClient(RestClient http, String token, long minIntervalMs) {
        this.http = http;
        String t = token == null ? "" : token.trim();
        this.token = t.regionMatches(true, 0, "Bearer ", 0, 7) ? t.substring(7).trim() : t;
        this.minIntervalMs = minIntervalMs;
    }

    public boolean available() {
        return !token.isBlank();
    }

    /** The series of the work this edition belongs to. Novellas (position 1.5) carry no volume. */
    public Optional<SeriesMatch> seriesByIsbn(String isbn13) {
        if (!available()) {
            return Optional.empty();
        }
        JsonNode editions = ask(EDITION, Map.of("isbn", isbn13)).path("editions");
        if (editions.isEmpty()) {
            return Optional.empty();
        }
        List<JsonNode> memberships = new ArrayList<>();
        editions.get(0).path("book").path("book_series").forEach(memberships::add);
        return memberships.stream()
                .filter(m -> wholeNumber(m.path("position")) != null)
                // the featured series, otherwise the narrowest one ("The Folk of the Air", not the umbrella "Elfhame")
                .min(Comparator.comparing((JsonNode m) -> !m.path("featured").asBoolean(false))
                        .thenComparingInt(m -> m.path("series").path("primary_books_count").asInt(Integer.MAX_VALUE)))
                .map(m -> new SeriesMatch(m.path("series").path("id").asLong(), m.path("series").path("name").asString(),
                        wholeNumber(m.path("position"))));
    }

    /**
     * Volumes 1..n. Several books share a position (translations, audio splits): the most read one counts,
     * and for German readers its German edition title.
     */
    public List<SeriesCatalogVolume> catalog(long seriesId, boolean german) {
        if (!available()) {
            return List.of();
        }
        JsonNode series = ask(SERIES, Map.of("id", seriesId)).path("series_by_pk");
        Map<Integer, JsonNode> best = new TreeMap<>();
        for (JsonNode bs : series.path("book_series")) {
            Integer n = wholeNumber(bs.path("position"));
            if (n == null || bs.path("compilation").asBoolean(false)) {
                continue;
            }
            JsonNode book = bs.path("book");
            best.merge(n, book, (a, b) -> b.path("users_count").asInt() > a.path("users_count").asInt() ? b : a);
        }
        return best.entrySet().stream().map(e -> {
            JsonNode book = e.getValue();
            JsonNode de = book.path("editions");
            String title = german && !de.isEmpty() && !de.get(0).path("title").asString("").isBlank()
                    ? de.get(0).path("title").asString() : book.path("title").asString();
            return new SeriesCatalogVolume(e.getKey(), title.trim());
        }).filter(v -> !v.title().isBlank()).toList();
    }

    /** Series search by name; the author has to match, the most read series wins. */
    public Optional<Long> findSeries(String name, String author) {
        if (!available() || name == null || name.isBlank()) {
            return Optional.empty();
        }
        String surname = author == null ? "" : Names.collationKey(Names.sortForm(author).split(",")[0]);
        List<JsonNode> hits = new ArrayList<>();
        ask(SEARCH, Map.of("q", name)).path("search").path("results").path("hits").forEach(h -> hits.add(h.path("document")));
        return hits.stream()
                .filter(d -> surname.isBlank() || Names.collationKey(d.path("author_name").asString("")).contains(surname))
                .filter(d -> Similarity.title(name, d.path("name").asString("")) >= 0.6)
                .max(Comparator.comparingInt(d -> d.path("readers_count").asInt()))
                .map(d -> d.path("id").asLong());
    }

    private JsonNode ask(String query, Map<String, Object> variables) {
        throttle();
        try {
            JsonNode body = http.post().uri(URL)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", query, "variables", variables))
                    .retrieve().body(JsonNode.class);
            if (body == null) {
                return tools.jackson.databind.node.MissingNode.getInstance();
            }
            if (body.has("errors")) {
                log.warn("Hardcover answered with errors: {}", body.path("errors"));
            }
            return body.path("data");
        } catch (RestClientException e) {
            log.warn("Hardcover request failed: {}", e.getMessage());
            return tools.jackson.databind.node.MissingNode.getInstance();
        }
    }

    private synchronized void throttle() {
        long wait = lastCall + minIntervalMs - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastCall = System.currentTimeMillis();
    }

    private static Integer wholeNumber(JsonNode position) {
        if (!position.isNumber()) {
            return null;
        }
        double p = position.asDouble();
        return p == Math.rint(p) && p >= 1 ? (int) p : null;
    }
}
