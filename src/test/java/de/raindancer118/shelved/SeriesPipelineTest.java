package de.raindancer118.shelved;

import com.jayway.jsonpath.JsonPath;
import de.raindancer118.shelved.ai.LibraryAi;
import de.raindancer118.shelved.book.BookFormat;
import de.raindancer118.shelved.metadata.BookMetadata;
import de.raindancer118.shelved.metadata.WikidataClient;
import de.raindancer118.shelved.series.SeriesCatalogVolume;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Series: national library first, then Wikidata, then the photo's guess, Claude last. Hand-made entries are never touched. */
@DirtiesContext
class SeriesPipelineTest extends IntegrationTestBase {

    static BookMetadata ringDesHimmels(String series, Integer volume) {
        return new BookMetadata("9783473585960", "Der Ring des Himmels", "Ring des Himmels", null, List.of("Chris Bradford"),
                "Bradford, Chris", "Ravensburger", 2016, 477, 210, BookFormat.PAPERBACK, "ger", 999, null, null, null, "DNB",
                series, volume);
    }

    @Test
    void catalogueSeriesIsTakenRightAwayWithoutAnyLookup() throws Exception {
        when(metadata.byIsbn("9783473585960")).thenReturn(Optional.of(ringDesHimmels("Samurai", 8)));
        String id = JsonPath.read(mvc.perform(post("/api/books/isbn").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isbn\":\"9783473585960\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seriesName").value("Samurai"))
                .andExpect(jsonPath("$.seriesVolume").value(8))
                .andReturn().getResponse().getContentAsString(), "$.id");
        Thread.sleep(300);
        verify(wikidata, never()).findSeries(any(), any());
        assertThat(ai.seriesQuestions).isEmpty();
        mvc.perform(get("/api/books/" + id)).andExpect(jsonPath("$.seriesSource").value("CATALOG"));
    }

    @Test
    void aWrongGuessFromThePhotoIsCorrectedByTheCatalogue() throws Exception {
        // the photo said "Samurai 2", the national library knows it is volume 8
        when(dnb.byIsbn("9783473585960")).thenReturn(Optional.of(ringDesHimmels("Samurai", 8)));
        mvc.perform(post("/api/books").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Der Ring des Himmels\",\"authors\":[\"Chris Bradford\"],\"isbn\":\"9783473585960\"}"))
                .andExpect(status().isCreated());
        await().untilAsserted(() -> mvc.perform(get("/api/books").param("q", "ring des himmels"))
                .andExpect(jsonPath("$[0].seriesName").value("Samurai"))
                .andExpect(jsonPath("$[0].seriesVolume").value(8)));
    }

    @Test
    void germanNamesAreNotReplacedByEnglishWikidataLabels() throws Exception {
        when(wikidata.findSeries("Die Beschenkte", "Kristin Cashore"))
                .thenReturn(Optional.of(new WikidataClient.SeriesMatch("Q57546104", "Seven Kingdoms Trilogy", 1, false)));
        ai.seriesAnswer = List.of(new LibraryAi.SeriesGuess("Die Beschenkte", "Die sieben Königreiche", 1));
        mvc.perform(post("/api/books").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Die Beschenkte\",\"authors\":[\"Kristin Cashore\"]}")).andExpect(status().isCreated());
        await().untilAsserted(() -> mvc.perform(get("/api/books").param("q", "beschenkte"))
                .andExpect(jsonPath("$[0].seriesName").value("Die sieben Königreiche"))
                .andExpect(jsonPath("$[0].seriesVolume").value(1)));
    }

    @Test
    void handMadeSeriesIsNeverOverwritten() throws Exception {
        when(dnb.byIsbn("9783473585960")).thenReturn(Optional.of(ringDesHimmels("Samurai", 8)));
        String id = JsonPath.read(mvc.perform(post("/api/books").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Der Ring des Himmels\",\"authors\":[\"Chris Bradford\"],\"isbn\":\"9783473585960\",\"seriesName\":\"Meine Samurai\",\"seriesVolume\":99}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        Thread.sleep(500);
        mvc.perform(get("/api/books/" + id))
                .andExpect(jsonPath("$.seriesName").value("Meine Samurai"))
                .andExpect(jsonPath("$.seriesSource").value("MANUAL"));
    }

    @Test
    void germanSeriesListComesFromTheNationalLibrary() throws Exception {
        when(metadata.byIsbn("9783473585960")).thenReturn(Optional.of(ringDesHimmels("Samurai", 8)));
        when(dnb.seriesCatalog("Samurai", "Chris Bradford")).thenReturn(List.of(
                new SeriesCatalogVolume(1, "Der Weg des Kämpfers"), new SeriesCatalogVolume(2, "Der Weg des Schwertes"),
                new SeriesCatalogVolume(8, "Der Ring des Himmels"), new SeriesCatalogVolume(9, "Die Rückkehr des Kriegers")));
        mvc.perform(post("/api/books/isbn").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"isbn\":\"9783473585960\"}"));
        String key = JsonPath.read(mvc.perform(get("/api/series")).andReturn().getResponse().getContentAsString(), "$[0].key");
        await().untilAsserted(() -> mvc.perform(get("/api/series/" + key))
                .andExpect(jsonPath("$.catalogSource").value("CATALOG"))
                .andExpect(jsonPath("$.knownTotal").value(4))
                .andExpect(jsonPath("$.volumes[1].title").value("Der Weg des Schwertes")));
        assertThat(ai.catalogQuestions).isEmpty();
    }
}
