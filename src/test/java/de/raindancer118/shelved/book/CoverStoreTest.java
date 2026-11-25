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

        assertThat(covers.catalogCover("9783000000002")).isEmpty();
        assertThat(covers.catalogCover("9783000000002")).isEmpty();
        server.verify();
    }

    @Test
    void onlyIsbnsReachTheDisk() {
        assertThat(covers.catalogCover("../../etc/passwd")).isEmpty();
        assertThat(covers.catalogCover("12345")).isEmpty();
    }
}
