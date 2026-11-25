package de.raindancer118.shelved.placement;

import de.raindancer118.shelved.book.BookFormat;

public final class ThicknessEstimator {

    // German trade books mostly use bulky "Werkdruck" paper. Measured on a real 76 cm shelf: 14 books,
    // 6330 pages, about 71 cm wide → 0.1 mm per page (classic thin novel paper would be ~0.07).
    private static final double MM_PER_PAGE = 0.1;
    private static final int TYPICAL_NOVEL_MM = 25;

    private ThicknessEstimator() {
    }

    /** Where a book's thickness comes from, best first. */
    public enum Source { MANUAL, PHOTO, PAGES }

    /** Typed in by hand, else measured on a shelf photo, else guessed from the page count. */
    public static int estimateMm(de.raindancer118.shelved.book.Book b) {
        if (b.getMeasuredThicknessMm() != null && b.getMeasuredThicknessMm() > 0 && (b.getThicknessMm() == null || b.getThicknessMm() <= 0)) {
            return b.getMeasuredThicknessMm();
        }
        return estimateMm(b.getThicknessMm(), b.getPageCount(), b.getFormat());
    }

    public static Source source(de.raindancer118.shelved.book.Book b) {
        if (b.getThicknessMm() != null && b.getThicknessMm() > 0) {
            return Source.MANUAL;
        }
        return b.getMeasuredThicknessMm() != null && b.getMeasuredThicknessMm() > 0 ? Source.PHOTO : Source.PAGES;
    }

    public static int estimateMm(Integer explicitMm, Integer pages, BookFormat format) {
        if (explicitMm != null && explicitMm > 0) {
            return explicitMm;
        }
        if (pages == null || pages <= 0) {
            return TYPICAL_NOVEL_MM;
        }
        int cover = format == BookFormat.HARDCOVER ? 6 : 1;
        return (int) Math.round(pages * MM_PER_PAGE) + cover;
    }
}
