package de.raindancer118.shelved;

import com.jayway.jsonpath.JsonPath;
import de.raindancer118.shelved.book.BookFormat;
import de.raindancer118.shelved.book.CoverBackfill;
import de.raindancer118.shelved.metadata.BookMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Books that came in without a cover (photo without a catalogue match, a source that was down) get one later. */
@DirtiesContext
class CoverBackfillTest extends IntegrationTestBase {

    @Autowired
    private CoverBackfill backfill;

    static BookMetadata abendrot() {
        return new BookMetadata("9783551581655", "Biss zum Abendrot", "Biss zum Abendrot", null, List.of("Stephenie Meyer"),
                "Meyer, Stephenie", "Carlsen", 2008, 560, null, BookFormat.HARDCOVER, "ger", 2190, null, null, null, "DNB");
    }

    @Test
    void aBookWithoutCoverGetsTheCoverOfItsEditionOrOfTheSameWork() throws Exception {
        String byHand = JsonPath.read(mvc.perform(post("/api/books").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Bis(s) zum Abendrot\",\"authors\":[\"Stephenie Meyer\"]}")).andReturn().getResponse().getContentAsString(), "$.id");
        String other = JsonPath.read(mvc.perform(post("/api/books").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Ganz etwas anderes\",\"authors\":[\"Niemand\"]}")).andReturn().getResponse().getContentAsString(), "$.id");
        when(metadata.search(eq("Bis(s) zum Abendrot"), eq("Stephenie Meyer"), any())).thenReturn(List.of(abendrot()));
        when(metadata.search(eq("Ganz etwas anderes"), any(), any())).thenReturn(List.of(abendrot())); // a wrong hit must not be taken
        when(covers.fetch(any(), any())).thenReturn(Optional.of("abendrot.jpg"));

        assertThat(backfill.fill()).isEqualTo(1);

        mvc.perform(get("/api/books/" + byHand)).andExpect(jsonPath("$.cover").isNotEmpty());
        mvc.perform(get("/api/books/" + other)).andExpect(jsonPath("$.cover").doesNotExist());
    }

    /** A cover that is only a source's "no cover" card counts as missing and is looked for again. */
    @Test
    void aPlaceholderCoverIsReplaced() throws Exception {
        when(metadata.byIsbn("9783551581655")).thenReturn(Optional.of(abendrot()));
        when(covers.fetch(any(), any())).thenReturn(Optional.of("grey.jpg"), Optional.of("real.jpg"));
        String id = JsonPath.read(mvc.perform(post("/api/books/isbn").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"isbn\":\"9783551581655\"}")).andReturn().getResponse().getContentAsString(), "$.id");
        when(covers.path("grey.jpg")).thenReturn(Optional.of(java.nio.file.Path.of("grey.jpg")));
        when(covers.isPlaceholder(java.nio.file.Path.of("grey.jpg"))).thenReturn(true);

        assertThat(backfill.fill()).isEqualTo(1);

        mvc.perform(get("/api/books/" + id)).andExpect(jsonPath("$.cover").value(org.hamcrest.Matchers.containsString("?v=f" + "real.jpg".hashCode())));
    }
}
