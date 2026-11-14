package de.raindancer118.shelved.metadata;

import de.raindancer118.shelved.series.SeriesCatalogVolume;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HardcoverClientTest {

    private MockRestServiceServer server;
    private HardcoverClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HardcoverClient(builder.build(), "hc_pat_test", 0);
    }

    private void answer(String containing, String fixture) {
        server.expect(requestTo("https://api.hardcover.app/v1/graphql"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer hc_pat_test"))
                .andExpect(content().string(containsString(containing)))
                .andRespond(withSuccess(new ClassPathResource("fixtures/" + fixture), MediaType.APPLICATION_JSON));
    }

    @Test
    void aGermanEditionLeadsToTheSeriesOfItsWork() {
        answer("9783570128053", "hardcover-edition-9783570128053.json");
        assertThat(client.seriesByIsbn("9783570128053"))
                .contains(new HardcoverClient.SeriesMatch(5464, "The Inheritance Cycle", 3));
    }

    @Test
    void theNarrowestSeriesWinsWhenABookIsInSeveral() {
        // "The Folk of the Air" (3 books) rather than the umbrella "Elfhame" (5)
        answer("9780316310277", "hardcover-edition-9780316310277.json");
        assertThat(client.seriesByIsbn("9780316310277"))
                .contains(new HardcoverClient.SeriesMatch(5470, "The Folk of the Air", 1));
    }

    @Test
    void volumeListUsesGermanTitlesForGermanReadersAndSkipsCompilationsAndNovellas() {
        answer("5464", "hardcover-series-5464.json");
        assertThat(client.catalog(5464, true)).containsExactly(
                new SeriesCatalogVolume(1, "Das Vermächtnis der Drachenreiter"),
                new SeriesCatalogVolume(2, "Der Auftrag des Ältesten"),
                new SeriesCatalogVolume(3, "Die Weisheit des Feuers"),
                new SeriesCatalogVolume(4, "Eragon - Das Erbe der Macht"),
                new SeriesCatalogVolume(5, "Murtagh - Eine dunkle Bedrohung"));
    }

    @Test
    void volumeListInEnglish() {
        answer("5464", "hardcover-series-5464.json");
        assertThat(client.catalog(5464, false)).extracting(SeriesCatalogVolume::title)
                .containsExactly("Eragon", "Eldest", "Brisingr", "Inheritance", "Murtagh");
    }

    @Test
    void findsASeriesByNameAndAuthor() {
        answer("Tintenwelt", "hardcover-search-tintenwelt.json");
        assertThat(client.findSeries("Tintenwelt", "Cornelia Funke")).contains(91125L);
    }

    @Test
    void withoutTokenNothingIsAsked() {
        HardcoverClient off = new HardcoverClient(RestClient.create(), "", 0);
        assertThat(off.seriesByIsbn("9783570128053")).isEmpty();
        assertThat(off.catalog(5464, true)).isEmpty();
    }
}
