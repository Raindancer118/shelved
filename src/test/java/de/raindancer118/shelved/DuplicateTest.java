package de.raindancer118.shelved;

import com.jayway.jsonpath.JsonPath;
import de.raindancer118.shelved.ai.LibraryAi;
import de.raindancer118.shelved.book.BookFormat;
import de.raindancer118.shelved.metadata.BookMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A book exists once; more copies only when the user says so. */
@DirtiesContext
class DuplicateTest extends IntegrationTestBase {

    static BookMetadata momo() {
        return new BookMetadata("9783522202107", "Momo", "Momo", null, List.of("Michael Ende"), "Ende, Michael",
                "Thienemann", 2005, 304, null, BookFormat.PAPERBACK, "ger", 1500, null, null, null, "DNB");
    }

    @Test
    void sameIsbnTwiceIsRefusedUnlessItIsAnotherCopy() throws Exception {
        when(metadata.byIsbn("9783522202107")).thenReturn(Optional.of(momo()));
        String id = JsonPath.read(mvc.perform(post("/api/books/isbn").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isbn\":\"9783522202107\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/books/isbn").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"isbn\":\"9783522202107\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingId").value(id))
                .andExpect(jsonPath("$.title").value("Momo"));

        mvc.perform(post("/api/books/isbn").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isbn\":\"9783522202107\",\"anotherCopy\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.copies").value(2));

        mvc.perform(get("/api/books")).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].copies").value(2));
        mvc.perform(get("/api/stats")).andExpect(jsonPath("$.books").value(2)).andExpect(jsonPath("$.listValueCents").value(3000));

        mvc.perform(post("/api/books/" + id + "/copies").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"copies\":1}"))
                .andExpect(jsonPath("$.copies").value(1));
    }

    @Test
    void sameTitleAndAuthorByHandIsADuplicateToo() throws Exception {
        mvc.perform(post("/api/books").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Der Schwarm\",\"authors\":[\"Frank Schätzing\"]}")).andExpect(status().isCreated());
        mvc.perform(post("/api/books").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Der Schwarm\",\"authors\":[\"Schätzing, Frank\"]}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/books?anotherCopy=true").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Der Schwarm\",\"authors\":[\"Frank Schätzing\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.copies").value(2));
    }

    @Test
    void theSameBookTwiceOnOnePhotoIsOnlyAddedOnceByDefault() throws Exception {
        ai.nextShelf = new LibraryAi.ShelfReading(List.of(
                new LibraryAi.SpineReading("Momo", List.of("Michael Ende"), "", "high", "", 1),
                new LibraryAi.SpineReading("Momo", List.of("Michael Ende"), "", "high", "", 1)), List.of());
        when(metadata.search(any(), any(), any())).thenReturn(List.of(momo()));
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "jpg", out);

        String scan = JsonPath.read(mvc.perform(multipart("/api/scans").file(new MockMultipartFile("image", "s.jpg", "image/jpeg", out.toByteArray()))
                .param("mode", "SHELF").with(csrf())).andReturn().getResponse().getContentAsString(), "$.id");
        await().untilAsserted(() -> mvc.perform(get("/api/scans/" + scan)).andExpect(jsonPath("$.status").value("DONE")));
        mvc.perform(get("/api/scans/" + scan))
                .andExpect(jsonPath("$.items[0].status").value("NEW"))
                .andExpect(jsonPath("$.items[1].status").value("DUPLICATE"));

        // both ticked, but the second is not marked as another copy: one book
        mvc.perform(post("/api/scans/" + scan + "/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"index\":0,\"take\":true},{\"index\":1,\"take\":true}]}"))
                .andExpect(jsonPath("$.created", hasSize(1)));
        mvc.perform(get("/api/books")).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].copies").value(1));
    }

    @Test
    void secondCopyFromAPhotoWhenTheUserSaysSo() throws Exception {
        ai.nextShelf = new LibraryAi.ShelfReading(List.of(
                new LibraryAi.SpineReading("Momo", List.of("Michael Ende"), "", "high", "", 1),
                new LibraryAi.SpineReading("Momo", List.of("Michael Ende"), "", "high", "", 1)), List.of());
        when(metadata.search(any(), any(), any())).thenReturn(List.of(momo()));
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "jpg", out);
        String scan = JsonPath.read(mvc.perform(multipart("/api/scans").file(new MockMultipartFile("image", "s.jpg", "image/jpeg", out.toByteArray()))
                .param("mode", "SHELF").with(csrf())).andReturn().getResponse().getContentAsString(), "$.id");
        await().untilAsserted(() -> mvc.perform(get("/api/scans/" + scan)).andExpect(jsonPath("$.status").value("DONE")));

        mvc.perform(post("/api/scans/" + scan + "/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"index\":0,\"take\":true},{\"index\":1,\"take\":true,\"anotherCopy\":true}]}"))
                .andExpect(jsonPath("$.created", hasSize(1)));
        mvc.perform(get("/api/books")).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].copies").value(2));
    }
}
