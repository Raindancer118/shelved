package de.raindancer118.shelved.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LocClientTest {

    @Test
    void readsAnUntracedSeriesStatementAndStripsCataloguePunctuation() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LocClient client = new LocClient(builder.build());
        server.expect(requestTo(startsWith("https://lx2.loc.gov/sru/lcdb")))
                .andExpect(r -> assertThat(URLDecoder.decode(r.getURI().getRawQuery(), StandardCharsets.UTF_8))
                        .contains("query=bath.isbn=9780316310277"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/loc-isbn-9780316310277.xml"), MediaType.APPLICATION_XML));

        BookMetadata m = client.byIsbn("9780316310277").orElseThrow();

        assertThat(m.title()).isEqualTo("The cruel prince");
        assertThat(m.authors()).containsExactly("Holly Black");
        assertThat(m.publisher()).isEqualTo("Little, Brown and Company");
        assertThat(m.publishedYear()).isEqualTo(2018);
        assertThat(m.seriesName()).isEqualTo("The Folk of the Air");
        assertThat(m.seriesVolume()).isEqualTo(1);
        assertThat(m.source()).isEqualTo("LOC");
    }

    @Test
    void germanCatalogsIgnoreBareSeriesStatementsBecauseTheyAreMostlyImprints() throws Exception {
        byte[] xml = new ClassPathResource("fixtures/loc-isbn-9780316310277.xml").getContentAsByteArray();
        assertThat(DnbClient.parse(xml).getFirst().seriesName()).isNull();
    }
}
