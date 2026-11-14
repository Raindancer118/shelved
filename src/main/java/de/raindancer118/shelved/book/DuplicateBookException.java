package de.raindancer118.shelved.book;

/** The library already has this book; adding it again needs an explicit "another copy". */
public class DuplicateBookException extends RuntimeException {

    private final transient Book existing;

    public DuplicateBookException(Book existing) {
        super("„" + existing.getTitle() + "“ hast du schon");
        this.existing = existing;
    }

    public Book existing() {
        return existing;
    }
}
