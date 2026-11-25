package de.raindancer118.shelved.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenLibraryClientTest {

    private MockRestServiceServer server;
    private OpenLibraryClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenLibraryClient(builder.build());
    }

    /** A German edition Open Library doesn't know still has the work's ratings, found by title and author. */
    @Test
    void readsTheRatingOfAWorkByTitleAndAuthor() {
        server.expect(requestTo(startsWith("https://openlibrary.org/search.json")))
                .andRespond(withSuccess("""
                        {"docs":[{"key":"/works/OL17860744W","title":"A Court of Thorns and Roses","ratings_average":4.15,"ratings_count":873}]}""",
                        MediaType.APPLICATION_JSON));
        var r = client.workRating("A Court of Thorns and Roses", "Sarah J. Maas").orElseThrow();
        assertThat(r.average()).isEqualTo(4.15);
        assertThat(r.count()).isEqualTo(873);
    }

    /** Ratings hang on the work, so the edition is asked for its work first. */
    @Test
    void readsTheRatingOfTheWorkBehindAnEdition() {
        server.expect(requestTo("https://openlibrary.org/isbn/9783423285872.json"))
                .andRespond(withSuccess("{\"works\":[{\"key\":\"/works/OL17860744W\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://openlibrary.org/works/OL17860744W/ratings.json"))
                .andRespond(withSuccess("{\"summary\":{\"average\":4.21,\"count\":1043}}", MediaType.APPLICATION_JSON));
        var r = client.rating("9783423285872").orElseThrow();
        assertThat(r.average()).isEqualTo(4.21);
        assertThat(r.count()).isEqualTo(1043);
    }

    @Test
    void looksUpByIsbn() {
        server.expect(requestTo("https://openlibrary.org/api/books?bibkeys=ISBN:9780552166591&format=json&jscmd=data"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/openlibrary-isbn-9780552166591.json"), MediaType.APPLICATION_JSON));

        BookMetadata m = client.byIsbn("9780552166591").orElseThrow();

        assertThat(m.title()).isEqualTo("The Colour of Magic");
        assertThat(m.authors()).containsExactly("Terry Pratchett");
        assertThat(m.publisher()).isEqualTo("Corgi Books");
        assertThat(m.publishedYear()).isEqualTo(1985);
        assertThat(m.pageCount()).isEqualTo(287);
        assertThat(m.coverUrl()).isEqualTo("https://covers.openlibrary.org/b/id/14647238-L.jpg");
        assertThat(m.source()).isEqualTo("OPENLIBRARY");
    }

    @Test
    void emptyAnswerMeansUnknown() {
        server.expect(requestTo(startsWith("https://openlibrary.org/api/books")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/openlibrary-isbn-9783492281980.json"), MediaType.APPLICATION_JSON));
        assertThat(client.byIsbn("9783492281980")).isEmpty();
    }

    @Test
    void searchReturnsCandidatesWithCovers() {
        server.expect(requestTo(startsWith("https://openlibrary.org/search.json")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/openlibrary-search-colour.json"), MediaType.APPLICATION_JSON));

        var hits = client.search("The Colour of Magic", "Pratchett");

        assertThat(hits).hasSize(3);
        assertThat(hits.getFirst().title()).isEqualTo("The Colour of Magic");
        assertThat(hits.getFirst().coverUrl()).isEqualTo("https://covers.openlibrary.org/b/id/14647238-L.jpg");
        assertThat(hits.getFirst().publishedYear()).isEqualTo(1983);
        assertThat(hits.getFirst().isbn13()).isEqualTo("9780861403240");
    }
}
