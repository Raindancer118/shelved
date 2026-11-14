package de.raindancer118.shelved.metadata;

import de.raindancer118.shelved.book.BookFormat;

import java.util.List;

public record BookMetadata(
        String isbn13,
        String title,
        String titleSort,
        String subtitle,
        List<String> authors,
        String authorSort,
        String publisher,
        Integer publishedYear,
        Integer pageCount,
        Integer heightMm,
        BookFormat format,
        String language,
        Integer listPriceCents,
        String descriptionUrl,
        String description,
        String coverUrl,
        String source,
        /** Literary series as catalogued (DNB 800/490/830), not publisher imprints. */
        String seriesName,
        Integer seriesVolume) {

    public BookMetadata(String isbn13, String title, String titleSort, String subtitle, List<String> authors,
                        String authorSort, String publisher, Integer publishedYear, Integer pageCount, Integer heightMm,
                        BookFormat format, String language, Integer listPriceCents, String descriptionUrl,
                        String description, String coverUrl, String source) {
        this(isbn13, title, titleSort, subtitle, authors, authorSort, publisher, publishedYear, pageCount, heightMm,
                format, language, listPriceCents, descriptionUrl, description, coverUrl, source, null, null);
    }
}
