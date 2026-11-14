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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DirtiesContext
class ScanFlowTest extends IntegrationTestBase {

    static BookMetadata meta(String isbn, String title, String author, String publisher) {
        return new BookMetadata(isbn, title, title, null, List.of(author), null, publisher, 2015, 300, null,
                BookFormat.PAPERBACK, "ger", 1000, null, null, null, "DNB");
    }

    static LibraryAi.SpineReading spine(String title, String author, String series, int vol, String confidence) {
        return spine(title, author, series, vol, confidence, 1);
    }

    static LibraryAi.SpineReading spine(String title, String author, String series, int vol, String confidence, int row) {
        return new LibraryAi.SpineReading(title, List.of(author), series, vol, "", confidence, title, row);
    }

    static byte[] widePhoto() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4800, 1200, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    @Test
    void shelfPhotoBecomesBooksWithTheirPlaceOnTheBoard() throws Exception {
        String shelf = JsonPath.read(mvc.perform(post("/api/shelves").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Flur\",\"boardWidthsMm\":[800,800,800]}"))
                .andReturn().getResponse().getContentAsString(), "$.id");

        // Kafka is already in the library
        when(metadata.byIsbn("9783596294312")).thenReturn(Optional.of(meta("9783596294312", "Der Process", "Franz Kafka", "Fischer")));
        mvc.perform(post("/api/books/isbn").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"isbn\":\"9783596294312\"}"))
                .andExpect(status().isCreated());

        ai.nextShelf = new LibraryAi.ShelfReading(List.of(
                spine("Die Farben der Magie", "Terry Pratchett", "Scheibenwelt", 1, "high"),
                spine("Der Process", "Franz Kafka", "", 0, "high"),
                spine("…drome der Stadt", "", "", 0, "low")));
        when(metadata.search(eq("Die Farben der Magie"), eq("Terry Pratchett"), any()))
                .thenReturn(List.of(meta("9783492280624", "Die Farben der Magie", "Terry Pratchett", "Piper")));
        when(metadata.search(eq("Der Process"), eq("Franz Kafka"), any()))
                .thenReturn(List.of(meta("9783596294312", "Der Process", "Franz Kafka", "Fischer")));
        when(metadata.search(eq("…drome der Stadt"), any(), any())).thenReturn(List.of());

        String scan = JsonPath.read(mvc.perform(multipart("/api/scans")
                        .file(new MockMultipartFile("image", "shelf.jpg", "image/jpeg", widePhoto()))
                        .param("mode", "SHELF").param("shelfId", shelf).param("board", "2")
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(), "$.id");

        await().untilAsserted(() -> mvc.perform(get("/api/scans/" + scan)).andExpect(jsonPath("$.status").value("DONE")));
        assertThat(ai.tileCounts).containsExactly(3); // 4:1 photo is read in three segments

        mvc.perform(get("/api/scans/" + scan))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].status").value("NEW"))
                .andExpect(jsonPath("$.items[0].candidate.isbn13").value("9783492280624"))
                .andExpect(jsonPath("$.items[1].status").value("OWNED"))
                .andExpect(jsonPath("$.items[2].status").value("UNSURE"));

        mvc.perform(post("/api/scans/" + scan + "/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"index\":0,\"take\":true},{\"index\":2,\"take\":false}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created", hasSize(1)))
                .andExpect(jsonPath("$.placed", hasSize(2)));

        mvc.perform(get("/api/books").param("q", "farben"))
                .andExpect(jsonPath("$[0].seriesName").value("Scheibenwelt"))
                .andExpect(jsonPath("$[0].location").value("R1 · B2 · 01"));
        mvc.perform(get("/api/books").param("q", "kafka"))
                .andExpect(jsonPath("$[0].location").value("R1 · B2 · 02"));

        // accepting twice is refused
        mvc.perform(post("/api/scans/" + scan + "/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }

    /** One photo of a whole bookcase: books on the second board in the photo land one board further down. */
    @Test
    void photoWithSeveralBoardsPlacesEachRowOnItsOwnBoard() throws Exception {
        String shelf = JsonPath.read(mvc.perform(post("/api/shelves").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Billy\",\"boardWidthsMm\":[800,800,800,800]}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        ai.nextShelf = new LibraryAi.ShelfReading(List.of(
                spine("Vortex", "Anna Benning", "Vortex", 1, "high", 1),
                spine("Dark Sigils", "Anna Benning", "Dark Sigils", 1, "high", 1),
                spine("City of Bones", "Cassandra Clare", "Chroniken der Unterwelt", 1, "high", 2),
                spine("Ganz unten", "Niemand", "", 0, "high", 3)));
        when(metadata.search(any(), any(), any())).thenAnswer(inv -> List.of(meta(null, inv.getArgument(0), inv.getArgument(1), "Verlag")));

        String scan = JsonPath.read(mvc.perform(multipart("/api/scans")
                        .file(new MockMultipartFile("image", "case.jpg", "image/jpeg", widePhoto()))
                        .param("mode", "SHELF").param("shelfId", shelf).param("board", "2").with(csrf()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        await().untilAsserted(() -> mvc.perform(get("/api/scans/" + scan)).andExpect(jsonPath("$.status").value("DONE")));
        mvc.perform(post("/api/scans/" + scan + "/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"index\":0,\"take\":true},{\"index\":1,\"take\":true},{\"index\":2,\"take\":true},{\"index\":3,\"take\":true}]}"))
                .andExpect(jsonPath("$.created", hasSize(4)))
                .andExpect(jsonPath("$.placed", hasSize(4)));

        mvc.perform(get("/api/books").param("q", "dark sigils")).andExpect(jsonPath("$[0].location").value("R1 · B2 · 02"));
        mvc.perform(get("/api/books").param("q", "city of bones")).andExpect(jsonPath("$[0].location").value("R1 · B3 · 01"));
        mvc.perform(get("/api/books").param("q", "ganz unten")).andExpect(jsonPath("$[0].location").value("R1 · B4 · 01"));
    }

    @Test
    void barcodePhotoGivesTheIsbnWithoutAnyAi() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(de.raindancer118.shelved.scan.BarcodeReaderTest.photoWithBarcode("9783492281980"), "png", out);
        mvc.perform(multipart("/api/barcode").file(new MockMultipartFile("image", "b.png", "image/png", out.toByteArray())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isbn").value("9783492281980"));
        assertThat(ai.tileCounts).isEmpty();

        var empty = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB), "png", empty);
        mvc.perform(multipart("/api/barcode").file(new MockMultipartFile("image", "e.png", "image/png", empty.toByteArray())).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void garbageUploadIsRejected() throws Exception {
        mvc.perform(multipart("/api/scans").file(new MockMultipartFile("image", "x.jpg", "image/jpeg", new byte[]{1, 2, 3})).with(csrf()))
                .andExpect(status().isBadRequest());
    }
}
