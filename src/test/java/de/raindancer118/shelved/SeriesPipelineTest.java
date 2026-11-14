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

    @org.springframework.beans.factory.annotation.Autowired
    de.raindancer118.shelved.series.SeriesService seriesService;
    @org.springframework.beans.factory.annotation.Autowired
    de.raindancer118.shelved.book.BookService bookService;

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

    static BookMetadata cruelPrince(String series, Integer volume) {
        return new BookMetadata("9780316310277", "The Cruel Prince", "Cruel Prince", null, List.of("Holly Black"),
                "Black, Holly", "Little, Brown", 2018, 370, 210, BookFormat.HARDCOVER, "eng", 1999, null, null, null, "GOOGLE",
                series, volume);
    }

    private String addCruelPrince() throws Exception {
        when(metadata.byIsbn("9780316310277")).thenReturn(Optional.of(cruelPrince(null, null)));
        return JsonPath.read(mvc.perform(post("/api/books/isbn").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"isbn\":\"9780316310277\"}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void k10plusNumbersWhatTheNationalLibraryLeavesOpen() throws Exception {
        BookMetadata sinkkalion = new BookMetadata("9783570133651", "Sinkkâlion", "Sinkkâlion", null, List.of("Peter Freund"),
                "Freund, Peter", "cbj", 2010, 400, 210, BookFormat.HARDCOVER, "ger", 1899, null, null, null, "K10PLUS", "Mysteria", 3);
        when(k10plus.byIsbn("9783570133651")).thenReturn(Optional.of(sinkkalion));
        String id = JsonPath.read(mvc.perform(post("/api/books").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Sinkkâlion\",\"authors\":[\"Peter Freund\"],\"isbn\":\"9783570133651\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        await().untilAsserted(() -> mvc.perform(get("/api/books/" + id))
                .andExpect(jsonPath("$.seriesName").value("Mysteria"))
                .andExpect(jsonPath("$.seriesVolume").value(3))
                .andExpect(jsonPath("$.seriesSource").value("CATALOG")));
        verify(wikidata, never()).findSeries(any(), any());
    }

    @Test
    void englishBooksAreLookedUpAtTheLibraryOfCongress() throws Exception {
        when(loc.byIsbn("9780316310277")).thenReturn(Optional.of(cruelPrince("The Folk of the Air", 1)));
        String id = addCruelPrince();
        await().untilAsserted(() -> mvc.perform(get("/api/books/" + id))
                .andExpect(jsonPath("$.seriesName").value("The Folk of the Air"))
                .andExpect(jsonPath("$.seriesSource").value("CATALOG")));
    }

    @Test
    void germanIsbnsAreNotSentToTheLibraryOfCongress() throws Exception {
        when(metadata.byIsbn("9783473585960")).thenReturn(Optional.of(ringDesHimmels(null, null)));
        mvc.perform(post("/api/books/isbn").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"isbn\":\"9783473585960\"}"));
        await().untilAsserted(() -> verify(wikidata).findSeries(any(), any()));
        verify(loc, never()).byIsbn(any());
    }

    @Test
    void openLibraryNumbersAnEditionNoCatalogueKnows() throws Exception {
        when(openLibrary.seriesByIsbn("9780316310277")).thenReturn(Optional.of(new de.raindancer118.shelved.metadata.OpenLibraryClient.SeriesHit("The Folk of the Air", 1)));
        String id = addCruelPrince();
        await().untilAsserted(() -> mvc.perform(get("/api/books/" + id))
                .andExpect(jsonPath("$.seriesName").value("The Folk of the Air"))
                .andExpect(jsonPath("$.seriesVolume").value(1))
                .andExpect(jsonPath("$.seriesSource").value("COMMUNITY")));
        verify(hardcover, never()).seriesByIsbn(any());
    }

    @Test
    void hardcoverKnowsSeriesAndVolumeListWhenNothingElseDoes() throws Exception {
        when(hardcover.seriesByIsbn("9780316310277"))
                .thenReturn(Optional.of(new de.raindancer118.shelved.metadata.HardcoverClient.SeriesMatch(5470, "The Folk of the Air", 1)));
        when(hardcover.catalog(5470, false)).thenReturn(List.of(new SeriesCatalogVolume(1, "The Cruel Prince"),
                new SeriesCatalogVolume(2, "The Wicked King"), new SeriesCatalogVolume(3, "The Queen of Nothing")));
        String id = addCruelPrince();
        await().untilAsserted(() -> mvc.perform(get("/api/books/" + id))
                .andExpect(jsonPath("$.seriesName").value("The Folk of the Air"))
                .andExpect(jsonPath("$.seriesSource").value("COMMUNITY")));
        String key = JsonPath.read(mvc.perform(get("/api/series")).andReturn().getResponse().getContentAsString(), "$[0].key");
        await().untilAsserted(() -> mvc.perform(get("/api/series/" + key))
                .andExpect(jsonPath("$.catalogSource").value("HARDCOVER"))
                .andExpect(jsonPath("$.knownTotal").value(3))
                .andExpect(jsonPath("$.missingCount").value(2)));
        assertThat(ai.seriesQuestions).isEmpty();
        assertThat(ai.catalogQuestions).isEmpty();
    }

    @Test
    void germanLibrariesKeepTheirListWhenHardcoverOnlyKnowsSlightlyMore() throws Exception {
        when(metadata.byIsbn("9783473585960")).thenReturn(Optional.of(ringDesHimmels("Samurai", 8)));
        when(k10plus.seriesCatalog("Samurai", "Chris Bradford")).thenReturn(List.of(
                new SeriesCatalogVolume(1, "Der Weg des Kämpfers"), new SeriesCatalogVolume(2, "Der Weg des Schwertes"),
                new SeriesCatalogVolume(3, "Der Weg des Drachen"), new SeriesCatalogVolume(8, "Der Ring des Himmels")));
        when(hardcover.findSeries("Samurai", "Chris Bradford")).thenReturn(Optional.of(77L));
        when(hardcover.catalog(77L, true)).thenReturn(List.of(new SeriesCatalogVolume(1, "The Way of the Warrior"),
                new SeriesCatalogVolume(2, "The Way of the Sword"), new SeriesCatalogVolume(3, "The Way of the Dragon"),
                new SeriesCatalogVolume(4, "The Ring of Earth"), new SeriesCatalogVolume(5, "The Ring of Water")));
        mvc.perform(post("/api/books/isbn").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"isbn\":\"9783473585960\"}"));
        String key = JsonPath.read(mvc.perform(get("/api/series")).andReturn().getResponse().getContentAsString(), "$[0].key");
        await().untilAsserted(() -> mvc.perform(get("/api/series/" + key))
                .andExpect(jsonPath("$.catalogSource").value("CATALOG"))
                .andExpect(jsonPath("$.volumes[2].title").value("Der Weg des Drachen")));
    }

    @Test
    void collectedWorksReadFromASpineAreNoSeries() throws Exception {
        // the spine said "Werkausgabe 7", as a shelf scan hands it over
        BookMetadata physiker = new BookMetadata(null, "Die Physiker", "Physiker", null, List.of("Friedrich Dürrenmatt"),
                "Dürrenmatt, Friedrich", "Diogenes", 1998, 96, 180, BookFormat.PAPERBACK, "ger", 1200, null, null, null, "DNB", null, null);
        String library = JsonPath.read(mvc.perform(get("/api/me")).andReturn().getResponse().getContentAsString(), "$.library.id");
        String id = bookService.createFromMetadata(library, physiker,
                new de.raindancer118.shelved.book.BookService.SeriesHint("Werkausgabe", 7), false).getId().toString();
        seriesService.resolvePending(library);
        await().untilAsserted(() -> mvc.perform(get("/api/books/" + id)).andExpect(jsonPath("$.seriesName").doesNotExist()));
    }

    @Test
    void aBookWhoseCatalogueIsUnreachableStaysOpenAndIsNotGivenToWeakerSources() throws Exception {
        when(dnb.byIsbn("9783473585960")).thenThrow(new de.raindancer118.shelved.metadata.CatalogUnavailableException("DNB", "429"));
        when(openLibrary.seriesByIsbn("9783473585960")).thenReturn(Optional.of(new de.raindancer118.shelved.metadata.OpenLibraryClient.SeriesHit("Young Samurai", 8)));
        String id = JsonPath.read(mvc.perform(post("/api/books").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Der Ring des Himmels\",\"authors\":[\"Chris Bradford\"],\"isbn\":\"9783473585960\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        await().untilAsserted(() -> verify(dnb, org.mockito.Mockito.atLeastOnce()).byIsbn("9783473585960"));
        Thread.sleep(300);
        mvc.perform(get("/api/books/" + id)).andExpect(jsonPath("$.seriesName").doesNotExist());
        verify(openLibrary, never()).seriesByIsbn(any());

        // the catalogue is back: the next round finds the series
        org.mockito.Mockito.reset(dnb);
        when(dnb.byIsbn("9783473585960")).thenReturn(Optional.of(ringDesHimmels("Samurai", 8)));
        seriesService.retryOpenBooks();
        await().untilAsserted(() -> mvc.perform(get("/api/books/" + id))
                .andExpect(jsonPath("$.seriesName").value("Samurai"))
                .andExpect(jsonPath("$.seriesSource").value("CATALOG")));
    }
}
