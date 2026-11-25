package de.raindancer118.shelved.book;

import de.raindancer118.shelved.config.ShelvedProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Covers for search hits come from our own disk after the first request. */
class CoverStoreTest {

    @TempDir
    Path data;
    private MockRestServiceServer server;
    private CoverStore covers;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        covers = new CoverStore(new ShelvedProperties(data, "local", null, 10, null, null), builder.build());
    }

    @Test
    void aCatalogCoverIsFetchedOnceAndThenServedFromDisk() {
        server.expect(ExpectedCount.once(), requestTo("https://portal.dnb.de/opac/mvb/cover?isbn=9783551551672"))
                .andRespond(withSuccess(new byte[5_000], MediaType.IMAGE_JPEG));

        Path first = covers.catalogCover("9783551551672").orElseThrow();
        Path again = covers.catalogCover("9783551551672").orElseThrow();

        assertThat(again).isEqualTo(first).exists();
        server.verify();
    }

    @Test
    void noCoverAnywhereIsRememberedSoHitListsDontAskAgain() {
        server.expect(ExpectedCount.once(), requestTo("https://portal.dnb.de/opac/mvb/cover?isbn=9783000000002")).andRespond(withResourceNotFound());
        server.expect(ExpectedCount.once(), requestTo("https://covers.openlibrary.org/b/isbn/9783000000002-L.jpg?default=false")).andRespond(withResourceNotFound());
        server.expect(ExpectedCount.once(), requestTo(GOOGLE + "9783000000002")).andRespond(withSuccess(new byte[1_269], MediaType.IMAGE_PNG));

        assertThat(covers.catalogCover("9783000000002")).isEmpty();
        assertThat(covers.catalogCover("9783000000002")).isEmpty();
        server.verify();
    }

    private static final String GOOGLE = "https://books.google.com/books/content?printsec=frontcover&img=1&zoom=1&vid=ISBN";

    /** English editions: neither the DNB nor Open Library know them, Google Books does. */
    @Test
    void googleBooksIsTheLastResort() {
        server.expect(requestTo("https://portal.dnb.de/opac/mvb/cover?isbn=9781250377272")).andRespond(withResourceNotFound());
        server.expect(requestTo("https://covers.openlibrary.org/b/isbn/9781250377272-L.jpg?default=false")).andRespond(withResourceNotFound());
        server.expect(requestTo(GOOGLE + "9781250377272")).andRespond(withSuccess(new byte[15_000], MediaType.IMAGE_JPEG));

        assertThat(covers.catalogCover("9781250377272")).isPresent();
        server.verify();
    }

    @Test
    void mockupsAreServedAsTheirFrontAndTheOriginalStays() throws Exception {
        byte[] mockup = Mockups.paperbackJpeg();
        Path original = covers.path(covers.save(java.util.UUID.randomUUID(), mockup, "jpg")).orElseThrow();

        Path flat = covers.flat(original);

        assertThat(flat).isNotEqualTo(original);
        assertThat(javax.imageio.ImageIO.read(flat.toFile()).getWidth()).isLessThan(javax.imageio.ImageIO.read(original.toFile()).getWidth());
        assertThat(java.nio.file.Files.readAllBytes(original)).isEqualTo(mockup);
        assertThat(covers.flat(original)).isEqualTo(flat);
    }

    /** Covers stored before mockups were cut are prepared once after the start, not on the first slow page view. */
    @Test
    void existingCoversArePreparedOnce() {
        covers.save(java.util.UUID.randomUUID(), Mockups.paperbackJpeg(), "jpg");
        covers.save(java.util.UUID.randomUUID(), new byte[]{1, 2, 3}, "jpg"); // unreadable: left alone, no error

        assertThat(covers.flattenAll()).isEqualTo(1);
        assertThat(covers.flattenAll()).isZero(); // already done
    }

    @Test
    void onlyIsbnsReachTheDisk() {
        assertThat(covers.catalogCover("../../etc/passwd")).isEmpty();
        assertThat(covers.catalogCover("12345")).isEmpty();
    }
}
