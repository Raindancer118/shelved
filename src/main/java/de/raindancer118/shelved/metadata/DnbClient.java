package de.raindancer118.shelved.metadata;

import org.springframework.web.client.RestClient;

/** Deutsche Nationalbibliothek: German titles, publisher numbering, list prices. */
public class DnbClient extends MarcSruClient {

    public DnbClient(RestClient http) {
        super(http, DNB);
    }
}
