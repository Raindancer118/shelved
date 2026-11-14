package de.raindancer118.shelved.metadata;

import org.springframework.web.client.RestClient;

/**
 * K10plus, the union catalogue of the GBV and SWB libraries. Numbers series the national library
 * leaves unnumbered (Mysteria 3) because academic and public libraries catalogue them again.
 */
public class K10plusClient extends MarcSruClient {

    static final Profile K10PLUS = new Profile("K10PLUS", "https://sru.k10plus.de/opac-de-627", "marcxml",
            "pica.isb", "pica.tit", "pica.per", "", false, 300);

    public K10plusClient(RestClient http) {
        super(http, K10PLUS);
    }
}
