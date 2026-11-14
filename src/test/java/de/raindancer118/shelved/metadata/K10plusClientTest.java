package de.raindancer118.shelved.metadata;

import de.raindancer118.shelved.series.SeriesCatalogVolume;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class K10plusClientTest {

    private MockRestServiceServer server;
    private K10plusClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new K10plusClient(builder.build());
    }

    private static org.springframework.test.web.client.RequestMatcher query(String expected) {
        return r -> assertThat(URLDecoder.decode(r.getURI().getRawQuery(), StandardCharsets.UTF_8)).contains("query=" + expected);
    }

    @Test
    void readsTheAuthorSeriesOfABookTheNationalLibraryDoesNotNumber() {
        server.expect(requestTo(startsWith("https://sru.k10plus.de/opac-de-627")))
                .andExpect(query("pica.isb=9783570133651"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/k10plus-isbn-9783570133651.xml"), MediaType.APPLICATION_XML));

        BookMetadata m = client.byIsbn("9783570133651").orElseThrow();

        assertThat(m.title()).isEqualTo("Sinkkâlion");
        assertThat(m.authors()).containsExactly("Peter Freund");
        assertThat(m.seriesName()).isEqualTo("Mysteria");
        assertThat(m.seriesVolume()).isEqualTo(3);
        assertThat(m.source()).isEqualTo("K10PLUS");
    }

    @Test
    void buildsTheVolumeListOfASeries() {
        server.expect(requestTo(startsWith("https://sru.k10plus.de/opac-de-627")))
                .andExpect(query("pica.tit=Mysteria and pica.per=Freund"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/k10plus-series-mysteria.xml"), MediaType.APPLICATION_XML));

        assertThat(client.seriesCatalog("Mysteria", "Peter Freund")).containsExactly(
                new SeriesCatalogVolume(1, "Das Tor des Feuers"),
                new SeriesCatalogVolume(2, "Ayani"),
                new SeriesCatalogVolume(3, "Sinkkâlion"));
    }

    @Test
    void waitsAndRetriesWhenTheCatalogueSaysTooManyRequests() {
        client.backoff(java.time.Duration.ofMillis(10));
        server.expect(requestTo(startsWith("https://sru.k10plus.de/opac-de-627")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests());
        server.expect(requestTo(startsWith("https://sru.k10plus.de/opac-de-627")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/k10plus-isbn-9783570133651.xml"), MediaType.APPLICATION_XML));

        assertThat(client.byIsbn("9783570133651")).isPresent();
    }

    @Test
    void anUnreachableCatalogueIsAnErrorNotAnEmptyAnswer() {
        // "nothing found" would mark a book as checked and drop its series
        client.backoff(java.time.Duration.ofMillis(1));
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(), requestTo(startsWith("https://sru.k10plus.de/opac-de-627")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.byIsbn("9783570133651"))
                .isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void anHtmlErrorPageIsNoEmptyResult() {
        server.expect(requestTo(startsWith("https://sru.k10plus.de/opac-de-627")))
                .andRespond(withSuccess("<!DOCTYPE html><html><body>Fehler</body></html>", MediaType.TEXT_HTML));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.byIsbn("9783570133651"))
                .isInstanceOf(CatalogUnavailableException.class);
    }
}
