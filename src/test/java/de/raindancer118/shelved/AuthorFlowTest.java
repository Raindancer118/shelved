package de.raindancer118.shelved;

import de.raindancer118.shelved.book.BookFormat;
import de.raindancer118.shelved.metadata.BookMetadata;
import de.raindancer118.shelved.metadata.OpenLibraryClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Any search can lead to an author: a round picture, a short bio, and their books in German. */
@DirtiesContext
class AuthorFlowTest extends IntegrationTestBase {

    static BookMetadata edition(String isbn, String title, int year, Integer price) {
        return new BookMetadata(isbn, title, title, null, List.of("Sarah J. Maas"), "Maas, Sarah J.", "dtv", year, 480, null,
                BookFormat.PAPERBACK, "ger", price, null, null, null, "DNB");
    }

    @Test
    void authorsAreFoundAndTheirProfileShowsTheirGermanBooksOncePerTitle() throws Exception {
        when(openLibrary.searchAuthors("maas")).thenReturn(List.of(
                new OpenLibraryClient.AuthorHit("OL7115219A", "Sarah J. Maas", "05 March 1986", "A Court of Thorns and Roses", 50),
                new OpenLibraryClient.AuthorHit("OL1A", "Michele Finey", null, null, 1))); // the search is fuzzy; not a Maas
        when(openLibrary.searchAuthors("Sarah J. Maas")).thenReturn(List.of(
                new OpenLibraryClient.AuthorHit("OL7115219A", "Sarah J. Maas", "05 March 1986", "A Court of Thorns and Roses", 50)));
        when(openLibrary.author("OL7115219A")).thenReturn(Optional.of(new OpenLibraryClient.AuthorDetail(
                "OL7115219A", "Sarah J. Maas", "05 March 1986", null, "Sarah J. Maas is the #1 New York Times bestselling author.", true)));
        when(dnb.byAuthor("Sarah J. Maas")).thenReturn(List.of(
                edition("9783423285872", "A Court of Thorns and Roses", 2026, 2600),
                edition("9783423230001", "A Court of Thorns and Roses", 2020, 1800), // older edition of the same title
                edition("9783423230002", "Throne of Glass", 2019, 1600)));

        mvc.perform(get("/api/authors").param("q", "maas"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Sarah J. Maas"))
                .andExpect(jsonPath("$[0].photo").value("/api/covers/author/OL7115219A"));

        mvc.perform(get("/api/authors/profile").param("name", "Sarah J. Maas"))
                .andExpect(jsonPath("$.name").value("Sarah J. Maas"))
                .andExpect(jsonPath("$.bio").value("Sarah J. Maas is the #1 New York Times bestselling author."))
                .andExpect(jsonPath("$.photo").value("/api/covers/author/OL7115219A"))
                .andExpect(jsonPath("$.books", hasSize(2)))
                .andExpect(jsonPath("$.books[0].isbn13").value("9783423285872"))
                .andExpect(jsonPath("$.books[1].title").value("Throne of Glass"));
    }

    @Test
    void anAuthorOnlyTheGermanLibraryKnowsStillHasAProfile() throws Exception {
        when(openLibrary.searchAuthors("Kate Corell")).thenReturn(List.of());
        when(dnb.byAuthor("Kate Corell")).thenReturn(List.of(edition("9783551585028", "Never Be My Date", 2022, 1500)));
        mvc.perform(get("/api/authors/profile").param("name", "Kate Corell"))
                .andExpect(jsonPath("$.name").value("Kate Corell"))
                .andExpect(jsonPath("$.photo").doesNotExist())
                .andExpect(jsonPath("$.books", hasSize(1)));
    }
}
