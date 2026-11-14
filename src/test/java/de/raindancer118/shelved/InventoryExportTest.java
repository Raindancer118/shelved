package de.raindancer118.shelved;

import com.jayway.jsonpath.JsonPath;
import de.raindancer118.shelved.ai.LibraryAi;
import de.raindancer118.shelved.book.BookFormat;
import de.raindancer118.shelved.metadata.BookMetadata;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Household insurance wants a list with replacement value and photos as proof of ownership. */
@DirtiesContext
class InventoryExportTest extends IntegrationTestBase {

    private static BookMetadata book(String isbn, String title, String author, Integer price, BookFormat format) {
        return new BookMetadata(isbn, title, title, null, List.of(author), null, "Piper", 2019, 300, 190,
                format, "ger", price, null, null, null, "DNB");
    }

    private void shelfWithPhotoAndBooks() throws Exception {
        String shelf = JsonPath.read(mvc.perform(post("/api/shelves").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Wohnzimmer\",\"boardWidthsMm\":[800,800]}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        ai.nextShelf = new LibraryAi.ShelfReading(List.of(
                ScanFlowTest.spine("Gevatter Tod", "Terry Pratchett", "", 0, "high")), List.of());
        when(metadata.search(any(), any(), any())).thenReturn(List.of(book("9783492281980", "Gevatter Tod", "Terry Pratchett", 1200, BookFormat.PAPERBACK)));
        String scan = JsonPath.read(mvc.perform(multipart("/api/scans")
                        .file(new MockMultipartFile("image", "shelf.jpg", "image/jpeg", ScanFlowTest.widePhoto()))
                        .param("mode", "SHELF").param("shelfId", shelf).param("board", "1").with(csrf()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        await().untilAsserted(() -> mvc.perform(get("/api/scans/" + scan)).andExpect(jsonPath("$.status").value("DONE")));
        mvc.perform(post("/api/scans/" + scan + "/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"index\":0,\"take\":true}]}")).andExpect(status().isOk());

        when(metadata.byIsbn("9783551551672")).thenReturn(Optional.of(book("9783551551672", "Harry Potter und der Stein der Weisen", "J. K. Rowling", 2400, BookFormat.HARDCOVER)));
        String hp = JsonPath.read(mvc.perform(post("/api/books/isbn").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"isbn\":\"9783551551672\"}")).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/books/" + hp + "/copies").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"copies\":2}"))
                .andExpect(status().isOk());
        // no price known: the list estimates and says so
        mvc.perform(post("/api/books").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Ohne Preis\",\"authors\":[\"Anna Benning\"]}")).andExpect(status().isCreated());
    }

    @Test
    void storesTheShelfPhotoOfAScan() throws Exception {
        shelfWithPhotoAndBooks();
        String photos = mvc.perform(get("/api/photos")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(photos, "$[0].url");
        assertThat((String) JsonPath.read(photos, "$[0].label")).contains("Wohnzimmer");
        byte[] jpg = mvc.perform(get(url)).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG)).andReturn().getResponse().getContentAsByteArray();
        var img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpg));
        assertThat(img.getWidth()).isLessThanOrEqualTo(2400); // 4800 px photo stored smaller
    }

    @Test
    void pdfListsEveryBookWithReplacementValueAndTheShelfPhotos() throws Exception {
        shelfWithPhotoAndBooks();
        byte[] pdf = mvc.perform(get("/api/export/inventory.pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsByteArray();

        PdfReader reader = new PdfReader(pdf);
        StringBuilder text = new StringBuilder();
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int p = 1; p <= reader.getNumberOfPages(); p++) {
            text.append(extractor.getTextFromPage(p)).append('\n');
        }
        String t = text.toString();
        assertThat(t).contains("Inventarliste", "Gevatter Tod", "Harry Potter und der Stein der Weisen", "Ohne Preis",
                "Wohnzimmer", "R1 · B1 · 01", "9783492281980");
        // 12,00 + 2 × 24,00 = 60,00 € known, the book without price is estimated at the median 18,00 €
        assertThat(t).contains("78,00 €").contains("18,00 €").contains("geschätzt");
        assertThat(t).contains("Regalfotos");
        assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void csvOpensInExcelWithGermanNumbers() throws Exception {
        shelfWithPhotoAndBooks();
        byte[] csv = mvc.perform(get("/api/export/inventory.csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andReturn().getResponse().getContentAsByteArray();
        String s = new String(csv, StandardCharsets.UTF_8);
        assertThat(s).startsWith("﻿"); // BOM, otherwise Excel shows umlauts wrong
        List<String> lines = s.substring(1).lines().toList();
        assertThat(lines.getFirst()).isEqualTo("Nr;Titel;Autor;Verlag;Jahr;ISBN;Format;Standort;Exemplare;Neupreis €;Wert €;Preisquelle");
        assertThat(lines).anyMatch(l -> l.contains("Harry Potter und der Stein der Weisen") && l.contains(";2;24,00;48,00;"));
    }
}
