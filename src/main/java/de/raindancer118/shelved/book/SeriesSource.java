package de.raindancer118.shelved.book;

/** Where a book's series came from, most trustworthy first. */
public enum SeriesSource {
    /** Typed or corrected by the user: never overwritten. */
    MANUAL,
    /** Library catalogue record of this edition (DNB, K10plus, Library of Congress). */
    CATALOG,
    WIKIDATA,
    /** Reader communities: Open Library's series field, Hardcover. */
    COMMUNITY,
    /** Read from the spine on a shelf photo. */
    PHOTO,
    /** Claude's guess when no catalogue knew the book. */
    AI
}
