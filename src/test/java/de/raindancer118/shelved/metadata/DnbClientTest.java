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
    void literarySeriesAndVolumeComeFromTheAuthorSeriesField() {
        BookMetadata m = isbn("9783473585960");
        assertThat(m.seriesName()).isEqualTo("Samurai");
        assertThat(m.seriesVolume()).isEqualTo(8);
    }

    @Test
    void publisherSeriesLikeGoldmannAreNotLiterarySeries() {
        BookMetadata m = isbn("9783442486823");
        assertThat(m.seriesName()).isEqualTo("Chroniken der Unterwelt");
        assertThat(m.seriesVolume()).isEqualTo(1);
    }

    @Test
    void printEditionCarriesItsSeriesNumbering() {
        BookMetadata m = isbn("9783570311707");
        assertThat(m.title()).isEqualTo("Das erste Jahr");
        assertThat(m.seriesName()).isEqualTo("Magic academy");
        assertThat(m.seriesVolume()).isEqualTo(1);
    }

    @Test
    void seriesNamesLoseTheirReiheSuffix() {
        assertThat(DnbClient.cleanSeries("Die Magic Academy-Reihe")).isEqualTo("Die Magic Academy");
        assertThat(DnbClient.volumeNumber("8. Band")).isEqualTo(8);
        assertThat(DnbClient.volumeNumber("Buch 1")).isEqualTo(1);
        assertThat(DnbClient.volumeNumber("8016 : Piper Fantasy")).isNull();
    }

    @Test
    void catalogueWordingIsTurnedIntoTheSeriesName() {
        assertThat(DnbClient.cleanSeries("\u0098Der ...\u009C Band der Tintenwelt-Reihe")).isEqualTo("Tintenwelt");
        assertThat(DnbClient.cleanSeries("... Roman des Zyklus von der Scheibenwelt")).isEqualTo("Scheibenwelt");
        assertThat(DnbClient.cleanSeries("Bd. ... der Mysteria-Trilogie")).isEqualTo("Mysteria");
        assertThat(DnbClient.cleanSeries("Mysteria-Trilogie / Peter Freund")).isEqualTo("Mysteria");
    }

    @Test
    void collectedWorksAndImprintsAreNoSeries() {
        assertThat(DnbClient.notALiterarySeries("Werkausgabe")).isTrue();
        assertThat(DnbClient.notALiterarySeries("Das dramatische Werk")).isTrue();
        assertThat(DnbClient.notALiterarySeries("Bild-am-Sonntag-Fantasy-Bibliothek")).isTrue();
        assertThat(DnbClient.notALiterarySeries("Klassiker in leichter Sprache")).isTrue();
        assertThat(DnbClient.notALiterarySeries("Samurai")).isFalse();
        assertThat(DnbClient.notALiterarySeries("Das Uhrwerk der Zeit")).isFalse();
        assertThat(DnbClient.notALiterarySeries("Chroniken der Unterwelt")).isFalse();
    }

    @Test
    void anImprintWithoutVolumeIsNoSeries() {
        assertThat(isbn("9783492281980").seriesName()).isNull();
    }

    @Test
    void imprintNumbersInSearchHitsAreIgnored() {
        server.expect(requestTo(startsWith("https://services.dnb.de/sru/dnb")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/dnb-search-farben.xml"), MediaType.APPLICATION_XML));
        assertThat(client.search("Die Farben der Magie", "Terry Pratchett")).extracting(BookMetadata::seriesName).containsOnlyNulls();
    }

    /** All volumes of a German series straight from the national library, no AI. */
    @Test
    void readsTheWholeSeriesFromTheCatalogue() {
        server.expect(requestTo(startsWith("https://services.dnb.de/sru/dnb")))
                .andExpect(decodedQuery("query=tit=Samurai and per=Bradford and mat=books"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/dnb-series-samurai.xml"), MediaType.APPLICATION_XML));

        var volumes = client.seriesCatalog("Samurai", "Chris Bradford");

        assertThat(volumes).extracting(de.raindancer118.shelved.series.SeriesCatalogVolume::number)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(volumes.get(1).title()).isEqualTo("Der Weg des Schwertes");
        assertThat(volumes.get(7).title()).isEqualTo("Der Ring des Himmels");
    }
}
