package de.raindancer118.shelved.book;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String what) {
        super(what + " nicht gefunden");
    }
}
