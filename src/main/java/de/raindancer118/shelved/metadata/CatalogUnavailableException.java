package de.raindancer118.shelved.metadata;

/** A catalogue could not answer (rate limit, outage). Not the same as "it doesn't know the book". */
public class CatalogUnavailableException extends RuntimeException {

    public CatalogUnavailableException(String source, String reason) {
        super(source + " nicht erreichbar: " + reason);
    }
}
