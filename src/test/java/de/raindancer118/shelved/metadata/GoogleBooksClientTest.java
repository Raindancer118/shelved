package de.raindancer118.shelved.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleBooksClientTest {

    private MockRestServiceServer server;
    private GoogleBooksClient client;
    private RestClient.Builder builder;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GoogleBooksClient(builder.build(), "test-key");
    }

    @Test
    void looksUpAGermanOmnibusByIsbn() {
        server.expect(requestTo(allOf(startsWith("https://www.googleapis.com/books/v1/volumes"),
                        containsString("q=isbn%3A9783492281980"), containsString("key=test-key"))))
                .andRespond(withSuccess(new ClassPathResource("fixtures/google-isbn-9783492281980.json"), MediaType.APPLICATION_JSON));

        BookMetadata m = client.byIsbn("9783492281980").orElseThrow();

        assertThat(m.title()).isEqualTo("Gevatter Tod . Das Erbe des Zauberers");
        assertThat(m.subtitle()).isEqualTo("Zwei Romane in einem Band");
        assertThat(m.authors()).containsExactly("Terry Pratchett");
        assertThat(m.authorSort()).isEqualTo("Pratchett, Terry");
        assertThat(m.publishedYear()).isEqualTo(2019);
        assertThat(m.pageCount()).isEqualTo(624);
        assertThat(m.language()).isEqualTo("ger");
        assertThat(m.coverUrl()).isNull();
        assertThat(m.source()).isEqualTo("GOOGLE");
    }

    @Test
    void englishEditionBringsCoverAndBlurb() {
        server.expect(requestTo(startsWith("https://www.googleapis.com/books/v1/volumes")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/google-isbn-9780552166591.json"), MediaType.APPLICATION_JSON));

        BookMetadata m = client.byIsbn("9780552166591").orElseThrow();

        assertThat(m.coverUrl()).startsWith("https://books.google.com/books/content").contains("fife=w600");
        assertThat(m.description()).contains("His spectacular inventiveness");
        assertThat(m.pageCount()).isNull(); // Google says 0
        assertThat(m.language()).isEqualTo("eng");
    }

    @Test
    void searchSkipsEbooksAndKeepsPrintCandidates() {
        server.expect(requestTo(startsWith("https://www.googleapis.com/books/v1/volumes")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/google-search-farben.json"), MediaType.APPLICATION_JSON));

        var hits = client.search("Die Farben der Magie", "Terry Pratchett");

        assertThat(hits).extracting(BookMetadata::publisher).doesNotContain("Piper ebooks");
        assertThat(hits).extracting(BookMetadata::title).contains("Die Farben der Magie");
        assertThat(hits).filteredOn(h -> "9783492285100".equals(h.isbn13())).singleElement()
                .satisfies(h -> assertThat(h.description()).startsWith("Der Magier Rincewind"));
    }

    @Test
    void withoutKeyTheClientStaysQuiet() {
        var noKey = new GoogleBooksClient(builder.build(), "");
        assertThat(noKey.byIsbn("9783492281980")).isEmpty();
        assertThat(noKey.search("x", "y")).isEmpty();
        server.verify(); // no request was made
    }
}
