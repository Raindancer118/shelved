package de.raindancer118.shelved.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WikidataClientTest {

    private MockRestServiceServer server;
    private WikidataClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new WikidataClient(builder.build());
    }

    @Test
    void picksTheMainSeriesOverASubSeries() {
        server.expect(requestTo(startsWith("https://query.wikidata.org/sparql")))
                .andExpect(request -> assertThat(java.net.URLDecoder.decode(request.getURI().getRawQuery(), java.nio.charset.StandardCharsets.UTF_8))
                        .contains("mwapi:search \"Die Farben der Magie\"").contains("\"pratchett\""))
                .andRespond(withSuccess(new ClassPathResource("fixtures/wikidata-work-farben.json"), MediaType.APPLICATION_JSON));

        var match = client.findSeries("Die Farben der Magie", "Terry Pratchett").orElseThrow();

        assertThat(match.seriesId()).isEqualTo("Q3257270");
        assertThat(match.seriesName()).isEqualTo("Scheibenwelt");
        assertThat(match.volume()).isEqualTo(1);
    }

    @Test
    void readsTheWholeCatalogInOrderWithGermanTitles() {
        server.expect(requestTo(startsWith("https://query.wikidata.org/sparql")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/wikidata-catalog-scheibenwelt.json"), MediaType.APPLICATION_JSON));

        var volumes = client.catalog("Q3257270", "de");

        assertThat(volumes).hasSize(41);
        assertThat(volumes.get(3).number()).isEqualTo(4);
        assertThat(volumes.get(3).title()).isEqualTo("Gevatter Tod");
        assertThat(volumes.getLast().title()).isEqualTo("Die Krone des Schäfers");
    }

    @Test
    void englishBooksGetEnglishTitles() {
        server.expect(requestTo(startsWith("https://query.wikidata.org/sparql")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/wikidata-catalog-scheibenwelt.json"), MediaType.APPLICATION_JSON));
        assertThat(client.catalog("Q3257270", "eng").get(3).title()).isEqualTo("Mort");
    }

    @Test
    void outagesMeanNoAnswerNotAnError() {
        server.expect(requestTo(startsWith("https://query.wikidata.org/sparql"))).andRespond(withServerError());
        assertThat(client.findSeries("X", "Y")).isEmpty();
    }

    @Test
    void cleansSeriesLabels() {
        assertThat(WikidataClient.cleanSeriesName("Scheibenwelt-Romane")).isEqualTo("Scheibenwelt");
        assertThat(WikidataClient.cleanSeriesName("Harry Potter")).isEqualTo("Harry Potter");
        assertThat(WikidataClient.cleanSeriesName("Die Tribute von Panem (Romanreihe)")).isEqualTo("Die Tribute von Panem");
        assertThat(WikidataClient.cleanSeriesName("Tintenwelt-Trilogie")).isEqualTo("Tintenwelt-Trilogie");
    }
}
