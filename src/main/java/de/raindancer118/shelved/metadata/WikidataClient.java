package de.raindancer118.shelved.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.raindancer118.shelved.book.Names;
import de.raindancer118.shelved.series.SeriesCatalogVolume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Series knowledge from Wikidata: "part of the series" (P179) with its "series ordinal" (P1545).
 * Free, no key, and it covers the popular series well – this is why shelved rarely needs an AI for series.
 */
public class WikidataClient {

    private static final Logger log = LoggerFactory.getLogger(WikidataClient.class);

    /** {@code german}: the name is Wikidata's German label (not an English fallback). */
    public record SeriesMatch(String seriesId, String seriesName, Integer volume, boolean german) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Value(String value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Results(List<Map<String, Value>> bindings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(Results results) {
    }

    private final RestClient http;

    public WikidataClient(RestClient http) {
        this.http = http;
    }

    public Optional<SeriesMatch> findSeries(String title, String author) {
        if (title == null || title.isBlank() || author == null || author.isBlank()) {
            return Optional.empty();
        }
        String surname = Names.collationKey(Names.sortForm(author).split(",")[0]);
        String query = """
                SELECT ?series ?seriesLabel ?deLabel ?ord (COUNT(DISTINCT ?sibling) AS ?size) WHERE {
                  SERVICE wikibase:mwapi {
                    bd:serviceParam wikibase:endpoint "www.wikidata.org"; wikibase:api "EntitySearch";
                                    mwapi:search "%s"; mwapi:language "de"; mwapi:limit "10".
                    ?book wikibase:apiOutputItem mwapi:item.
                  }
                  ?book p:P179 ?st . ?st ps:P179 ?series .
                  OPTIONAL { ?st pq:P1545 ?ord . }
                  ?book wdt:P50 ?author . ?author rdfs:label ?authorLabel .
                  FILTER(LANG(?authorLabel) IN ("de","en") && CONTAINS(LCASE(STR(?authorLabel)), "%s"))
                  OPTIONAL { ?sibling wdt:P179 ?series . }
                  OPTIONAL { ?series rdfs:label ?deLabel . FILTER(LANG(?deLabel) = "de") }
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "de,en". }
                } GROUP BY ?series ?seriesLabel ?deLabel ?ord""".formatted(literal(title), literal(surname));
        // the biggest series wins: "Scheibenwelt-Romane" (63 works) over the sub-series "Rincewind" (8)
        return run(query).stream()
                .max(Comparator.comparingInt(b -> parseInt(b, "size", 0)))
                .map(b -> new SeriesMatch(qid(b.get("series").value()),
                        cleanSeriesName((b.containsKey("deLabel") ? b.get("deLabel") : b.get("seriesLabel")).value()),
                        b.containsKey("ord") && parseInt(b, "ord", 0) > 0 ? parseInt(b, "ord", 0) : null,
                        b.containsKey("deLabel")));
    }

    public List<SeriesCatalogVolume> catalog(String seriesId, String language) {
        if (seriesId == null || !seriesId.matches("Q\\d+")) {
            return List.of();
        }
        String query = """
                SELECT ?item ?ord ?de ?en WHERE {
                  ?item p:P179 ?st . ?st ps:P179 wd:%s ; pq:P1545 ?ord .
                  OPTIONAL { ?item rdfs:label ?de . FILTER(LANG(?de) = "de") }
                  OPTIONAL { ?item rdfs:label ?en . FILTER(LANG(?en) = "en") }
                }""".formatted(seriesId);
        boolean german = language == null || language.startsWith("ger") || language.startsWith("de");
        Map<Integer, String> byNumber = new LinkedHashMap<>();
        for (Map<String, Value> b : run(query)) {
            int n = parseInt(b, "ord", -1);
            String de = b.containsKey("de") ? b.get("de").value() : null;
            String en = b.containsKey("en") ? b.get("en").value() : null;
            String title = german ? (de != null ? de : en) : (en != null ? en : de);
            if (n > 0 && title != null) {
                byNumber.putIfAbsent(n, title);
            }
        }
        return byNumber.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new SeriesCatalogVolume(e.getKey(), e.getValue()))
                .toList();
    }

    static String cleanSeriesName(String label) {
        return de.raindancer118.shelved.series.SeriesNames.normalize(label);
    }

    private List<Map<String, Value>> run(String sparql) {
        URI uri = UriComponentsBuilder.fromUriString("https://query.wikidata.org/sparql")
                .queryParam("query", "{q}")
                .encode(StandardCharsets.UTF_8)
                .buildAndExpand(sparql)
                .toUri();
        try {
            Response r = http.get().uri(uri).header("Accept", "application/sparql-results+json").retrieve().body(Response.class);
            return r == null || r.results() == null || r.results().bindings() == null ? List.of() : r.results().bindings();
        } catch (RestClientException e) {
            log.warn("Wikidata query failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String literal(String s) {
        return s.replace("\\", " ").replace("\"", " ").replace("\n", " ").trim();
    }

    private static String qid(String uri) {
        return uri.substring(uri.lastIndexOf('/') + 1);
    }

    private static int parseInt(Map<String, Value> b, String key, int fallback) {
        Value v = b.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return (int) Double.parseDouble(v.value());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
