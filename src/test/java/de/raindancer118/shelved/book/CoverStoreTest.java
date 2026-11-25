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
import java.util.List;

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
        covers = new CoverStore(new ShelvedProperties(data, "local", null, 10, null, null, null), builder.build());
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

    /** Self-published titles often have no ISBN in Google's answer, only Google's own volume id. */
    @Test
    void aCoverByGoogleVolumeIdIsFetchedOnce() {
        server.expect(ExpectedCount.once(), requestTo("https://books.google.com/books/content?id=zyTCAlFPjgYC&printsec=frontcover&img=1&zoom=1&fife=w600"))
                .andRespond(withSuccess(new byte[9_000], MediaType.IMAGE_JPEG));
        assertThat(covers.googleCover("zyTCAlFPjgYC")).isPresent();
        assertThat(covers.googleCover("zyTCAlFPjgYC")).isPresent();
        assertThat(covers.googleCover("../etc")).isEmpty();
        server.verify();
    }

    /** Google answers "no cover" with a plain grey card; that is not a cover, the next source is asked. */
    @Test
    void aPlainGreyPlaceholderIsNoCover() throws Exception {
        var grey = new java.awt.image.BufferedImage(600, 863, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = grey.createGraphics();
        g.setColor(new java.awt.Color(158, 160, 164));
        g.fillRect(0, 0, 600, 863);
        g.setColor(new java.awt.Color(170, 172, 176));
        g.fillRect(150, 300, 300, 20); // the faint stripes
        g.dispose();
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(grey, "jpg", out);

        server.expect(requestTo("https://example.org/placeholder.jpg")).andRespond(withSuccess(out.toByteArray(), MediaType.IMAGE_JPEG));
        server.expect(requestTo("https://example.org/real.jpg")).andRespond(withSuccess(Mockups.paperbackJpeg(), MediaType.IMAGE_JPEG));

        String file = covers.fetch(java.util.UUID.randomUUID(), List.of("https://example.org/placeholder.jpg", "https://example.org/real.jpg")).orElseThrow();
        assertThat(covers.isPlaceholder(covers.path(file).orElseThrow())).isFalse();
        server.verify();
    }

    @Test
    void onlyIsbnsReachTheDisk() {
        assertThat(covers.catalogCover("../../etc/passwd")).isEmpty();
        assertThat(covers.catalogCover("12345")).isEmpty();
    }
}
