package de.raindancer118.shelved;

import com.jayway.jsonpath.JsonPath;
import de.raindancer118.shelved.book.BookFormat;
import de.raindancer118.shelved.metadata.BookMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Books you want; once one of them is in the library, the app asks whether it may leave the list. */
@DirtiesContext
class WishlistTest extends IntegrationTestBase {

    static BookMetadata tintenherz() {
        return new BookMetadata("9783791504650", "Tintenherz", "Tintenherz", null, List.of("Cornelia Funke"), "Funke, Cornelia",
                "Dressler", 2003, 576, null, BookFormat.HARDCOVER, "ger", 2490, null, null, null, "DNB");
    }

    private ResultActions postJson(String path, String json) throws Exception {
        return mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void wishByIsbnIsListedOnceWithCatalogData() throws Exception {
        when(metadata.byIsbn("9783791504650")).thenReturn(Optional.of(tintenherz()));

        String id = JsonPath.read(postJson("/api/wishes", "{\"isbn\":\"978-3-7915-0465-0\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Tintenherz"))
                .andExpect(jsonPath("$.listPriceCents").value(2490))
                .andReturn().getResponse().getContentAsString(), "$.id");

        // the same book again is not a second wish
        postJson("/api/wishes", "{\"isbn\":\"9783791504650\"}").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/wishes")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ownedBookId").value(nullValue()));

        // a wish opens like a book
        mvc.perform(get("/api/wishes/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Tintenherz"))
                .andExpect(jsonPath("$.publisher").value("Dressler"));

        mvc.perform(delete("/api/wishes/" + id).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/wishes/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/wishes")).andExpect(jsonPath("$", hasSize(0)));
    }

    /** A wish shows its blurb like a book does, without the catalogue's HTML codes. */
    @Test
    void aWishCarriesItsBlurbWithoutHtmlCodes() throws Exception {
        when(metadata.byIsbn("9783791504650")).thenReturn(Optional.of(tintenherz()));
        when(metadata.description(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of("Meggie liest&#160;gern &amp; viel."));
        String id = JsonPath.read(postJson("/api/wishes", "{\"isbn\":\"9783791504650\"}").andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/api/wishes/" + id)).andExpect(jsonPath("$.description").value("Meggie liest\u00a0gern & viel."));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private de.raindancer118.shelved.wishlist.WishRepository wishRepository;

    /** A publisher's home page was once stored as blurb; opening the wish fetches the real one. */
    @Test
    void aStoredWebPageIsReplacedByTheRealBlurb() throws Exception {
        when(metadata.byIsbn("9783791504650")).thenReturn(Optional.of(tintenherz()));
        when(metadata.description(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of("Meggie liest gern."));
        String id = JsonPath.read(postJson("/api/wishes", "{\"isbn\":\"9783791504650\"}").andReturn().getResponse().getContentAsString(), "$.id");
        var w = wishRepository.findById(java.util.UUID.fromString(id)).orElseThrow();
        w.setDescription(String.join("\n", java.util.Collections.nCopies(80, "Kinderbuch\nMerkzettel")));
        wishRepository.save(w);

        mvc.perform(get("/api/wishes/" + id)).andExpect(jsonPath("$.description").value("Meggie liest gern."));
    }

    @Test
    void aBookYouAlreadyOwnIsNotWished() throws Exception {
        when(metadata.byIsbn("9783791504650")).thenReturn(Optional.of(tintenherz()));
        String book = JsonPath.read(postJson("/api/books/isbn", "{\"isbn\":\"9783791504650\"}")
                .andReturn().getResponse().getContentAsString(), "$.id");

        postJson("/api/wishes", "{\"isbn\":\"9783791504650\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingId").value(book));
    }

    @Test
    void addingAWishedBookAsksToRemoveIt() throws Exception {
        when(metadata.byIsbn("9783791504650")).thenReturn(Optional.of(tintenherz()));
        String byIsbn = JsonPath.read(postJson("/api/wishes", "{\"isbn\":\"9783791504650\"}").andReturn().getResponse().getContentAsString(), "$.id");
        // without an ISBN: title and author as typed
        String byTitle = JsonPath.read(postJson("/api/wishes",
                "{\"title\":\"Tintenblut\",\"authors\":[\"Cornelia Funke\"]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String other = JsonPath.read(postJson("/api/wishes", "{\"title\":\"Momo\",\"authors\":[\"Michael Ende\"]}")
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/wishes/fulfilled")).andExpect(jsonPath("$", hasSize(0)));

        postJson("/api/books/isbn", "{\"isbn\":\"9783791504650\"}").andExpect(status().isCreated());
        String tintenblut = JsonPath.read(postJson("/api/books", "{\"title\":\"Tintenblut\",\"authors\":[\"Funke, Cornelia\"]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/wishes/fulfilled"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.id == '" + byTitle + "')].ownedBookId").value(tintenblut));

        // take one off the list, keep the other (e.g. a gift for someone else)
        postJson("/api/wishes/resolve", "{\"remove\":[\"" + byIsbn + "\"],\"keep\":[\"" + byTitle + "\"]}")
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/wishes/fulfilled")).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/wishes"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.id == '" + byTitle + "')].ownedBookId").value(tintenblut))
                .andExpect(jsonPath("$[?(@.id == '" + other + "')].ownedBookId").value(org.hamcrest.Matchers.contains(nullValue())));
    }

    @Test
    void aWishNeedsATitleOrAnIsbn() throws Exception {
        postJson("/api/wishes", "{\"authors\":[\"Michael Ende\"]}").andExpect(status().isBadRequest());
        when(metadata.byIsbn("9783522202107")).thenReturn(Optional.empty());
        postJson("/api/wishes", "{\"isbn\":\"9783522202107\"}").andExpect(status().isNotFound());
    }
}
