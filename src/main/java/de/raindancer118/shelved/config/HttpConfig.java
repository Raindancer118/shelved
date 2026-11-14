package de.raindancer118.shelved.config;

import de.raindancer118.shelved.metadata.DnbClient;
import de.raindancer118.shelved.metadata.GoogleBooksClient;
import de.raindancer118.shelved.metadata.WikidataClient;
import de.raindancer118.shelved.metadata.OpenLibraryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class HttpConfig {

    static final String USER_AGENT = "shelved/0.1 (private library app; +https://shelf.tstieh.de)";

    @Bean
    RestClient externalHttp(RestClient.Builder builder) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(15));
        return builder.requestFactory(factory).defaultHeader("User-Agent", USER_AGENT).build();
    }

    @Bean
    DnbClient dnbClient(RestClient externalHttp) {
        return new DnbClient(externalHttp);
    }

    @Bean
    GoogleBooksClient googleBooksClient(RestClient externalHttp, ShelvedProperties props) {
        return new GoogleBooksClient(externalHttp, props.googleBooksApiKey());
    }

    @Bean
    WikidataClient wikidataClient(RestClient externalHttp) {
        return new WikidataClient(externalHttp);
    }

    @Bean
    OpenLibraryClient openLibraryClient(RestClient externalHttp) {
        return new OpenLibraryClient(externalHttp);
    }
}
