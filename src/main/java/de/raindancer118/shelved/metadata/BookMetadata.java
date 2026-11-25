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
        String source) {
}
