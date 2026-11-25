package de.raindancer118.shelved.metadata;

/** Average of 1–5 stars at one source, with how many people rated. */
public record Rating(String source, double average, int count) {
}
