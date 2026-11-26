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

    static LibraryAi.SpineReading spine(String title, String author, String confidence) {
        return spine(title, author, confidence, 1);
    }

    static LibraryAi.SpineReading spine(String title, String author, String confidence, int row) {
        return new LibraryAi.SpineReading(title, List.of(author), "", confidence, title, row, 0);
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
                spine("Die Farben der Magie", "Terry Pratchett", "high"),
                spine("Der Process", "Franz Kafka", "high"),
                spine("…drome der Stadt", "", "low")), List.of());
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
                spine("Vortex", "Anna Benning", "high", 1),
                spine("Dark Sigils", "Anna Benning", "high", 1),
                spine("City of Bones", "Cassandra Clare", "high", 2),
                spine("Ganz unten", "Niemand", "high", 3)), List.of());
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

    /** Unknown board width: the photo tells how full the board is, the books tell how wide they are. */
    @Test
    void boardWidthIsEstimatedFromThePhotoWhenUnknown() throws Exception {
        String shelf = JsonPath.read(mvc.perform(post("/api/shelves").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Neu\",\"boardWidthsMm\":[null,500]}"))
                .andExpect(jsonPath("$.boardsEstimated[0]").value(true))
                .andExpect(jsonPath("$.boardsEstimated[1]").value(false))
                .andReturn().getResponse().getContentAsString(), "$.id");
        ai.nextShelf = new LibraryAi.ShelfReading(List.of(
                spine("Eins", "A Autor", "high", 1),
                spine("Zwei", "B Autor", "high", 1),
                spine("Drei", "C Autor", "high", 2)),
                List.of(new LibraryAi.BoardReading(1, 0.25, true), new LibraryAi.BoardReading(2, 0.1, true)));
        // 300 pages paperback ≈ 20 mm each → 40 mm fill a quarter → ~160 mm board
        when(metadata.search(any(), any(), any())).thenAnswer(inv -> List.of(meta(null, inv.getArgument(0), inv.getArgument(1), "Verlag")));

        String scan = JsonPath.read(mvc.perform(multipart("/api/scans")
                        .file(new MockMultipartFile("image", "s.jpg", "image/jpeg", widePhoto()))
                        .param("mode", "SHELF").param("shelfId", shelf).param("board", "1").with(csrf()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        await().untilAsserted(() -> mvc.perform(get("/api/scans/" + scan)).andExpect(jsonPath("$.status").value("DONE")));
        mvc.perform(post("/api/scans/" + scan + "/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"index\":0,\"take\":true},{\"index\":1,\"take\":true},{\"index\":2,\"take\":true}]}"))
                .andExpect(jsonPath("$.estimatedBoards", hasSize(1)))
                .andExpect(jsonPath("$.estimatedBoards[0].board").value(1))
                .andExpect(jsonPath("$.estimatedBoards[0].widthMm").value(160));

        mvc.perform(get("/api/shelves"))
                .andExpect(jsonPath("$[0].boardWidthsMm[0]").value(160))
                .andExpect(jsonPath("$[0].boardsEstimated[0]").value(true))
                .andExpect(jsonPath("$[0].boardWidthsMm[1]").value(500)); // measured width is never overwritten
    }

    @Test
    void aPhotoWithoutChoosingAModeIsOneBookOrAShelfDependingOnWhatIsOnIt() throws Exception {
        when(metadata.search(any(), any(), any())).thenReturn(List.of());
        ai.nextShelf = new LibraryAi.ShelfReading(List.of(spine("Momo", "Michael Ende", "high")), List.of());
        String single = JsonPath.read(mvc.perform(multipart("/api/scans").file(new MockMultipartFile("image", "p.jpg", "image/jpeg", widePhoto()))
                .param("mode", "AUTO").with(csrf())).andReturn().getResponse().getContentAsString(), "$.id");
        await().untilAsserted(() -> mvc.perform(get("/api/scans/" + single)).andExpect(jsonPath("$.status").value("DONE")));
        mvc.perform(get("/api/scans/" + single)).andExpect(jsonPath("$.mode").value("BOOK")).andExpect(jsonPath("$.items", hasSize(1)));
        assertThat(ai.tileCounts).isEmpty();

        // several spines: read again as a shelf (tiles, photo kept as evidence for the insurance list)
        ai.nextShelf = new LibraryAi.ShelfReading(List.of(spine("A", "X", "high"), spine("B", "X", "high"),
                spine("C", "X", "high"), spine("D", "X", "high")), List.of());
        String shelf = JsonPath.read(mvc.perform(multipart("/api/scans").file(new MockMultipartFile("image", "p.jpg", "image/jpeg", widePhoto()))
                .param("mode", "AUTO").with(csrf())).andReturn().getResponse().getContentAsString(), "$.id");
        await().untilAsserted(() -> mvc.perform(get("/api/scans/" + shelf)).andExpect(jsonPath("$.status").value("DONE")));
        mvc.perform(get("/api/scans/" + shelf)).andExpect(jsonPath("$.mode").value("SHELF")).andExpect(jsonPath("$.items", hasSize(4)));
        assertThat(ai.tileCounts).isNotEmpty();
    }

    static LibraryAi.SpineReading spine(String title, String author, double share) {
        return new LibraryAi.SpineReading(title, List.of(author), "", "high", title, 1, share);
    }

    private String shelfScan(String shelf, String board) throws Exception {
        String scan = JsonPath.read(mvc.perform(multipart("/api/scans").file(new MockMultipartFile("image", "shelf.jpg", "image/jpeg", widePhoto()))
                        .param("mode", "SHELF").param("shelfId", shelf).param("board", board).with(csrf()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        await().untilAsserted(() -> mvc.perform(get("/api/scans/" + scan)).andExpect(jsonPath("$.status").value("DONE")));
        return scan;
    }

    private String bookId(String q) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/books").param("q", q)).andReturn().getResponse().getContentAsString(), "$[0].id");
    }

    /** How wide each spine really is comes from the photo: on a measured board its share of the width is millimetres. */
    @Test
    void eachSpinesWidthOnTheShelfPhotoBecomesTheBooksThickness() throws Exception {
        String shelf = JsonPath.read(mvc.perform(post("/api/shelves").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Flur\",\"boardWidthsMm\":[800]}")).andReturn().getResponse().getContentAsString(), "$.id");
        when(metadata.search(any(), any(), any())).thenReturn(List.of());
        // a 941-page paperback the page count would make 95 mm thick
        ai.nextShelf = new LibraryAi.ShelfReading(List.of(spine("Dünnes Taschenbuch", "A", 0.05), spine("Dickes Hardcover", "B", 0.10)),
                List.of(new LibraryAi.BoardReading(1, 0.15, true)));
        String scan = shelfScan(shelf, "1");
        mvc.perform(post("/api/scans/" + scan + "/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"index\":0,\"take\":true},{\"index\":1,\"take\":true}]}")).andExpect(status().isOk());

        mvc.perform(get("/api/books/" + bookId("dünnes"))).andExpect(jsonPath("$.estimatedThicknessMm").value(40))
                .andExpect(jsonPath("$.thicknessSource").value("PHOTO"));
        mvc.perform(get("/api/books/" + bookId("dickes"))).andExpect(jsonPath("$.estimatedThicknessMm").value(80));
    }

    /** Without a measured board there are no millimetres on the photo, but the spines still split the estimated total right. */
    @Test
    void onAnUnmeasuredBoardTheSpinesShareTheEstimatedTotal() throws Exception {
        String shelf = JsonPath.read(mvc.perform(post("/api/shelves").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Flur\",\"boardWidthsMm\":[null]}")).andReturn().getResponse().getContentAsString(), "$.id");
        when(metadata.search(eq("Erstes"), any(), any())).thenReturn(List.of(meta("9783000000019", "Erstes", "A", "X")));
        when(metadata.search(eq("Zweites"), any(), any())).thenReturn(List.of(meta("9783000000026", "Zweites", "B", "X")));
        // both 300 pages: 20 mm each by page count, 40 mm together; the photo shows one three times as wide as the other
        ai.nextShelf = new LibraryAi.ShelfReading(List.of(spine("Erstes", "A", 0.05), spine("Zweites", "B", 0.15)),
                List.of(new LibraryAi.BoardReading(1, 0.2, true)));
        String scan = shelfScan(shelf, "1");
        mvc.perform(post("/api/scans/" + scan + "/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"index\":0,\"take\":true},{\"index\":1,\"take\":true}]}")).andExpect(status().isOk());

        mvc.perform(get("/api/books/" + bookId("erstes"))).andExpect(jsonPath("$.estimatedThicknessMm").value(10));
        mvc.perform(get("/api/books/" + bookId("zweites"))).andExpect(jsonPath("$.estimatedThicknessMm").value(30));
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

    @org.springframework.beans.factory.annotation.Autowired
    de.raindancer118.shelved.scan.ScanRepository scanRepository;
    @org.springframework.beans.factory.annotation.Autowired
    de.raindancer118.shelved.scan.ScanService scanService;

    @Test
    void scansInterruptedByARestartEndAsFailedInsteadOfLoadingForever() throws Exception {
        String library = JsonPath.read(mvc.perform(get("/api/me")).andReturn().getResponse().getContentAsString(), "$.library.id");
        var stuck = scanRepository.save(new de.raindancer118.shelved.scan.Scan(library, de.raindancer118.shelved.scan.Scan.Mode.SHELF, null, null));
        assertThat(scanService.failInterrupted()).isEqualTo(1);
        mvc.perform(get("/api/scans/" + stuck.getId()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Neustart")));
    }

    @Test
    void garbageUploadIsRejected() throws Exception {
        mvc.perform(multipart("/api/scans").file(new MockMultipartFile("image", "x.jpg", "image/jpeg", new byte[]{1, 2, 3})).with(csrf()))
                .andExpect(status().isBadRequest());
    }
}
