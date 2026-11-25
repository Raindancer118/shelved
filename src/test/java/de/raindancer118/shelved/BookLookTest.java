package de.raindancer118.shelved;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What the 3D book on the detail page needs: the back, and the colour of the page edges. */
@DirtiesContext
class BookLookTest extends IntegrationTestBase {

    private String book(String subtitle) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/books").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Fourth Wing\",\"subtitle\":\"" + subtitle + "\",\"authors\":[\"Rebecca Yarros\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void edgeColourCanBeSetClearedAndSwitchedOff() throws Exception {
        String id = book("Roman");
        mvc.perform(get("/api/books/" + id)).andExpect(jsonPath("$.edgeColor").value(nullValue()));
        mvc.perform(put("/api/books/" + id + "/edge").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"color\":\"#7A1F2B\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.edgeColor").value("#7a1f2b"));
        mvc.perform(put("/api/books/" + id + "/edge").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"color\":\"none\"}"))
                .andExpect(jsonPath("$.edgeColor").value("none"));
        mvc.perform(put("/api/books/" + id + "/edge").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"color\":\"red; x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/books/" + id + "/edge").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"color\":null}"))
                .andExpect(jsonPath("$.edgeColor").value(nullValue()));
    }

    @Test
    void backCoverIsStoredServedAndRemoved(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        String id = book("Roman");
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(60, 90, BufferedImage.TYPE_INT_RGB), "jpg", jpg);
        Path stored = Files.write(dir.resolve("back.jpg"), jpg.toByteArray());
        when(covers.save(any(), any(), eq("jpg"))).thenReturn("back.jpg");
        when(covers.path("back.jpg")).thenReturn(Optional.of(stored));
        mvc.perform(multipart("/api/books/" + id + "/back").file(new MockMultipartFile("image", "b.jpg", "image/jpeg", jpg.toByteArray()))
                        .with(r -> { r.setMethod("PUT"); return r; }).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backCover").value(startsWith("/api/books/" + id + "/back")));
        mvc.perform(get("/api/books/" + id + "/back")).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
        mvc.perform(delete("/api/books/" + id + "/back").with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.backCover").value(nullValue()));
        verify(covers).delete("back.jpg");
        mvc.perform(get("/api/books/" + id + "/back")).andExpect(status().isNotFound());
    }
}
