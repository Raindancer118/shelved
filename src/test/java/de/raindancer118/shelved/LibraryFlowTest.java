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
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DirtiesContext
class LibraryFlowTest extends IntegrationTestBase {

    static BookMetadata gevatterTod() {
        return new BookMetadata("9783492281980", "Gevatter Tod", "Gevatter Tod", null, List.of("Terry Pratchett"),
                "Pratchett, Terry", "Piper", 2019, 613, 190, BookFormat.PAPERBACK, "ger", 1200, null, null, null, "DNB");
    }

    @Test
    void wholeLibraryFlow() throws Exception {
        // a shelf with two 40 cm boards
        mvc.perform(post("/api/shelves").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Wohnzimmer\",\"boardWidthsMm\":[400,400]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(1));

        // add by ISBN (catalogue lookup) and by hand
        when(metadata.byIsbn("9783492281980")).thenReturn(Optional.of(gevatterTod()));
        when(wikidata.findSeries("Gevatter Tod", "Terry Pratchett"))
                .thenReturn(Optional.of(new WikidataClient.SeriesMatch("Q3257270", "Scheibenwelt", 4)));
        when(wikidata.catalog("Q3257270", "ger")).thenReturn(List.of(
                new SeriesCatalogVolume(1, "Die Farben der Magie"), new SeriesCatalogVolume(2, "Das Licht der Phantasie"),
                new SeriesCatalogVolume(3, "Das Erbe des Zauberers"), new SeriesCatalogVolume(4, "Gevatter Tod"),
                new SeriesCatalogVolume(5, "Der Zauberhut")));
        String created = mvc.perform(post("/api/books/isbn").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isbn\":\"978-3-492-28198-0\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Gevatter Tod"))
                .andExpect(jsonPath("$.listPriceCents").value(1200))
                .andExpect(jsonPath("$.usedPriceCents").value(350))
                .andReturn().getResponse().getContentAsString();
        String tod = JsonPath.read(created, "$.id");

        mvc.perform(post("/api/books").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Der Process","authors":["Franz Kafka"],"pageCount":280,"listPriceCents":700,
                                 "seriesName":""}"""))
                .andExpect(status().isCreated());

        // Wikidata puts Gevatter Tod into Scheibenwelt, no AI involved
        await().untilAsserted(() -> mvc.perform(get("/api/books/" + tod))
                .andExpect(jsonPath("$.seriesName").value("Scheibenwelt"))
                .andExpect(jsonPath("$.seriesVolume").value(4)));

        // sorted by author surname: Kafka before Pratchett
        mvc.perform(get("/api/books"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].title").value("Der Process"))
                .andExpect(jsonPath("$[1].title").value("Gevatter Tod"));

        // search finds by author and by ISBN
        mvc.perform(get("/api/books").param("q", "pratchett")).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/books").param("q", "978-3-492-28198-0")).andExpect(jsonPath("$[0].title").value("Gevatter Tod"));

        // "hab ich das?" by barcode
        mvc.perform(get("/api/lookup").param("isbn", "9783492281980"))
                .andExpect(jsonPath("$.owned", hasSize(1)))
                .andExpect(jsonPath("$.metadata.title").value("Gevatter Tod"));

        // placement: both books are new on board 1 of shelf 1
        mvc.perform(get("/api/placement"))
                .andExpect(jsonPath("$.toPlace").value(2))
                .andExpect(jsonPath("$.boards[0].books", hasSize(2)))
                .andExpect(jsonPath("$.boards[0].books[0].status").value("NEW"));

        // series: opening it loads the volume list from Wikidata
        String key = JsonPath.read(mvc.perform(get("/api/series")).andReturn().getResponse().getContentAsString(), "$[0].key");
        await().untilAsserted(() -> mvc.perform(get("/api/series/" + key))
                .andExpect(jsonPath("$.name").value("Scheibenwelt"))
                .andExpect(jsonPath("$.catalogStatus").value("DONE"))
                .andExpect(jsonPath("$.catalogSource").value("WIKIDATA"))
                .andExpect(jsonPath("$.missingCount").value(4))
                .andExpect(jsonPath("$.volumes[2].title").value("Das Erbe des Zauberers"))
                .andExpect(jsonPath("$.volumes[3].book.title").value("Gevatter Tod")));
        assertThat(ai.seriesQuestions).isEmpty();
        assertThat(ai.catalogQuestions).isEmpty();

        // stats
        mvc.perform(get("/api/stats"))
                .andExpect(jsonPath("$.books").value(2))
                .andExpect(jsonPath("$.listValueCents").value(1900))
                .andExpect(jsonPath("$.shelves").value(1));

        // edit and delete
        mvc.perform(put("/api/books/" + tod).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Gevatter Tod\",\"authors\":[\"Terry Pratchett\"],\"thicknessMm\":33,\"seriesName\":\"Scheibenwelt\",\"seriesVolume\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimatedThicknessMm").value(33));
        mvc.perform(delete("/api/books/" + tod).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/books/" + tod)).andExpect(status().isNotFound());
    }

    /** Claude only fills what Wikidata doesn't know, batched, and only for books without a series yet. */
    @Test
    void claudeIsOnlyTheFallback() throws Exception {
        ai.seriesAnswer = List.of(new LibraryAi.SeriesGuess("Tintenherz", "Tintenwelt", 1));
        ai.catalogAnswer = new LibraryAi.SeriesCatalog(List.of(new LibraryAi.CatalogVolume(1, "Tintenherz"),
                new LibraryAi.CatalogVolume(2, "Tintenblut"), new LibraryAi.CatalogVolume(3, "Tintentod")), true, "Trilogie");
        mvc.perform(post("/api/books").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Tintenherz\",\"authors\":[\"Cornelia Funke\"]}"))
                .andExpect(status().isCreated());

        await().untilAsserted(() -> mvc.perform(get("/api/books").param("q", "tintenherz"))
                .andExpect(jsonPath("$[0].seriesName").value("Tintenwelt")));
        assertThat(ai.seriesQuestions).containsExactly(List.of("Tintenherz"));

        String key = JsonPath.read(mvc.perform(get("/api/books").param("q", "tintenherz")).andReturn().getResponse().getContentAsString(), "$[0].id");
        String seriesKey = JsonPath.read(mvc.perform(get("/api/books/" + key)).andReturn().getResponse().getContentAsString(), "$.seriesKey");
        await().untilAsserted(() -> mvc.perform(get("/api/series/" + seriesKey))
                .andExpect(jsonPath("$.catalogSource").value("CLAUDE"))
                .andExpect(jsonPath("$.missingCount").value(2)));
        assertThat(ai.catalogQuestions).containsExactly("Tintenwelt");
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        mvc.perform(post("/api/books/isbn").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"isbn\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Keine gültige ISBN"));
        mvc.perform(post("/api/books").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/shelves").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"boardWidthsMm\":[10]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void writesNeedACsrfToken() throws Exception {
        mvc.perform(post("/api/books").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

}
