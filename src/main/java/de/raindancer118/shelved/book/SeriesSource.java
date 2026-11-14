package de.raindancer118.shelved.book;

/** Where a book's series came from, most trustworthy first. */
public enum SeriesSource {
    /** Typed or corrected by the user: never overwritten. */
    MANUAL,
    /** National library record of this edition (DNB 800/490/830). */
    CATALOG,
    WIKIDATA,
    /** Read from the spine on a shelf photo. */
    PHOTO,
    /** Claude's guess when no catalogue knew the book. */
    AI
}
