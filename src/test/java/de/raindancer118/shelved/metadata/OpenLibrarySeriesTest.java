package de.raindancer118.shelved.metadata;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenLibrarySeriesTest {

    @Test
    void readsTheSeriesFieldOfAnEdition() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenLibraryClient client = new OpenLibraryClient(builder.build());
        server.expect(requestTo("https://openlibrary.org/isbn/9780552166591.json"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/openlibrary-edition-9780552166591.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://openlibrary.org/isbn/9780316310277.json"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/openlibrary-edition-9780316310277.json"), MediaType.APPLICATION_JSON));

        assertThat(client.seriesByIsbn("9780552166591")).contains(new OpenLibraryClient.SeriesHit("Discworld", 1));
        // a series without a number tells nothing about the order
        assertThat(client.seriesByIsbn("9780316310277")).isEmpty();
    }

    @Test
    void understandsTheUsualWaysToWriteAVolume() {
        assertThat(OpenLibraryClient.parseSeries("The Kingkiller Chronicle, Book 1")).contains(new OpenLibraryClient.SeriesHit("The Kingkiller Chronicle", 1));
        assertThat(OpenLibraryClient.parseSeries("The Folk of the Air ; 2")).contains(new OpenLibraryClient.SeriesHit("The Folk of the Air", 2));
        assertThat(OpenLibraryClient.parseSeries("Discworld #41")).contains(new OpenLibraryClient.SeriesHit("Discworld", 41));
        assertThat(OpenLibraryClient.parseSeries("Mistborn (3)")).contains(new OpenLibraryClient.SeriesHit("Mistborn", 3));
        assertThat(OpenLibraryClient.parseSeries("Tintenwelt-Trilogie, Bd. 2")).contains(new OpenLibraryClient.SeriesHit("Tintenwelt", 2));
        assertThat(OpenLibraryClient.parseSeries("Penguin classics")).isEmpty();
        assertThat(OpenLibraryClient.parseSeries("Penguin classics ; 1234")).isEmpty();
        assertThat(OpenLibraryClient.parseSeries("Goldmann ; 48682")).isEmpty();
    }

    @Test
    void anUnknownIsbnIsSimplyNoSeries() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://openlibrary.org/isbn/9783000000000.json"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound());
        assertThat(new OpenLibraryClient(builder.build()).seriesByIsbn("9783000000000")).isEmpty();
    }
}
