package de.raindancer118.shelved.metadata;

import org.springframework.web.client.RestClient;

/** Library of Congress: English-language series ("The Folk of the Air ; 1"), mostly as a bare series statement. */
public class LocClient extends MarcSruClient {

    static final Profile LOC = new Profile("LOC", "https://lx2.loc.gov/sru/lcdb", "marcxml",
            "bath.isbn", "dc.title", "dc.creator", "", true, 700);

    public LocClient(RestClient http) {
        super(http, LOC);
    }
}
