package de.raindancer118.shelved.metadata;

import de.raindancer118.shelved.book.BookFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

class DnbClientTest {

    private MockRestServiceServer server;
    private DnbClient client;

    private static org.springframework.test.web.client.RequestMatcher decodedQuery(String expected) {
        return request -> assertThat(java.net.URLDecoder.decode(request.getURI().getRawQuery(), java.nio.charset.StandardCharsets.UTF_8))
                .contains(expected);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DnbClient(builder.build());
    }

    @Test
    void looksUpAGermanPaperbackByIsbn() {
        server.expect(requestTo(startsWith("https://services.dnb.de/sru/dnb")))
                .andExpect(decodedQuery("query=num=9783492281980"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/dnb-isbn-9783492281980.xml"), MediaType.APPLICATION_XML));

        BookMetadata m = client.byIsbn("9783492281980").orElseThrow();

        assertThat(m.isbn13()).isEqualTo("9783492281980");
        assertThat(m.title()).isEqualTo("Gevatter Tod");
        assertThat(m.authors()).containsExactly("Terry Pratchett");
        assertThat(m.authorSort()).isEqualTo("Pratchett, Terry");
        assertThat(m.publisher()).isEqualTo("Piper");
        assertThat(m.publishedYear()).isEqualTo(2019);
        assertThat(m.pageCount()).isEqualTo(613);
        assertThat(m.heightMm()).isEqualTo(190);
        assertThat(m.listPriceCents()).isEqualTo(1200);
        assertThat(m.format()).isEqualTo(BookFormat.PAPERBACK);
        assertThat(m.language()).isEqualTo("ger");
        assertThat(m.descriptionUrl()).isEqualTo("https://services.dnb.de/plus/idn/1169591035/blurb/");
        assertThat(m.coverUrl()).isEqualTo("https://portal.dnb.de/opac/mvb/cover?isbn=9783492281980");
        assertThat(m.source()).isEqualTo("DNB");
    }

    @Test
    void searchKeepsOnlyPrintedEditionsAndCleansTitles() {
        server.expect(requestTo(startsWith("https://services.dnb.de/sru/dnb")))
                .andExpect(decodedQuery("query=tit=Die Farben der Magie and per=Pratchett and mat=books"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/dnb-search-farben.xml"), MediaType.APPLICATION_XML));

        var hits = client.search("Die Farben der Magie", "Terry Pratchett");

        assertThat(hits).extracting(BookMetadata::isbn13)
                .containsExactly("9783492280167", "9783492280624", "9783492267168", "9783492286275");
        assertThat(hits.getFirst().title()).isEqualTo("Die Farben der Magie");
        assertThat(hits.getFirst().titleSort()).isEqualTo("Farben der Magie");
        assertThat(hits.get(2).format()).isEqualTo(BookFormat.HARDCOVER);
        assertThat(hits.get(1).listPriceCents()).isEqualTo(999);
    }

    @Test
    void unknownIsbnGivesNothing() {
        server.expect(requestTo(startsWith("https://services.dnb.de/sru/dnb")))
                .andRespond(withSuccess("""
                        <?xml version="1.0" encoding="UTF-8"?><searchRetrieveResponse xmlns="http://www.loc.gov/zing/srw/"><version>1.1</version><numberOfRecords>0</numberOfRecords></searchRetrieveResponse>""",
                        MediaType.APPLICATION_XML));
        assertThat(client.byIsbn("9780000000002")).isEmpty();
    }

    @Test
    void audioBooksAndOnlineResourcesAreNotBooksOnAShelf() {
        server.expect(requestTo(startsWith("https://services.dnb.de/sru/dnb")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/dnb-search-schwarm.xml"), MediaType.APPLICATION_XML));

        var hits = client.search("Der Schwarm", "Frank Schätzing");

        assertThat(hits).extracting(BookMetadata::publisher).doesNotContain("Der Hörverlag");
        assertThat(hits).extracting(BookMetadata::isbn13).contains("9783462005103").doesNotContain("9783844549041", "9783844511093");
    }

    private BookMetadata isbn(String isbn) {
        server.expect(requestTo(startsWith("https://services.dnb.de/sru/dnb")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/dnb-isbn-" + isbn + ".xml"), MediaType.APPLICATION_XML));
        return client.byIsbn(isbn).orElseThrow();
    }

    @Test
    void aPartOfAMultiPartWorkIsTheBook() {
        assertThat(isbn("9783570311707").title()).isEqualTo("Das erste Jahr");
    }

    @Test
    void thePartTitleIsTheTitle() {
        String xml = """
                <searchRetrieveResponse><records><record><recordData>
                <record xmlns="http://www.loc.gov/MARC21/slim">
                  <datafield tag="245" ind1="1" ind2="0">
                    <subfield code="a">... Roman des Zyklus von der Scheibenwelt</subfield>
                    <subfield code="n">1.</subfield>
                    <subfield code="p">Das Licht der Phantasie</subfield>
                  </datafield>
                </record></recordData></record></records></searchRetrieveResponse>""";
        BookMetadata m = DnbClient.parse(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)).getFirst();
        assertThat(m.title()).isEqualTo("Das Licht der Phantasie");
    }
}
