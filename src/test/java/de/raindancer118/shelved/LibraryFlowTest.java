package de.raindancer118.shelved;

import com.jayway.jsonpath.JsonPath;
import de.raindancer118.shelved.book.BookFormat;
import de.raindancer118.shelved.metadata.BookMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

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
        String created = mvc.perform(post("/api/books/isbn").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isbn\":\"978-3-492-28198-0\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Gevatter Tod"))
                .andExpect(jsonPath("$.listPriceCents").value(1200))
                .andExpect(jsonPath("$.usedPriceCents").value(200))
                .andExpect(jsonPath("$.buybackPriceCents").value(40))
                .andReturn().getResponse().getContentAsString();
        String tod = JsonPath.read(created, "$.id");

        mvc.perform(post("/api/books").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Der Process","authors":["Franz Kafka"],"pageCount":280,"listPriceCents":700}"""))
                .andExpect(status().isCreated());

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

        // stats
        mvc.perform(get("/api/stats"))
                .andExpect(jsonPath("$.books").value(2))
                .andExpect(jsonPath("$.listValueCents").value(1900))
                .andExpect(jsonPath("$.shelves").value(1));

        // edit and delete
        mvc.perform(put("/api/books/" + tod).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Gevatter Tod\",\"authors\":[\"Terry Pratchett\"],\"thicknessMm\":33}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimatedThicknessMm").value(33));
        mvc.perform(delete("/api/books/" + tod).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/books/" + tod)).andExpect(status().isNotFound());
    }

    /** Series were removed as a feature: no endpoint, no columns, no catalogue table left behind (V10). */
    /** The local bookshop to order from; its product page is the ISBN under the shop's address. */
    @Test
    void theLocalBookshopIsOffered() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(jsonPath("$.bookshop.name").value("Buchhandlung Cremer"))
                .andExpect(jsonPath("$.bookshop.url").value("https://buchhandlungcremer.buchkatalog.de/{isbn}"));
    }

    @Test
    void seriesAreGone() throws Exception {
        mvc.perform(get("/api/series")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name in ('book', 'wish') and column_name like 'series%'""", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name = 'series_catalog'",
                Integer.class)).isZero();
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
