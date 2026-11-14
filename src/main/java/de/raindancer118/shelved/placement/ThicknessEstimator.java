package de.raindancer118.shelved.placement;

import de.raindancer118.shelved.book.BookFormat;

public final class ThicknessEstimator {

    // ~0.068 mm per page for typical 70–80 g novel paper, plus the cover
    private static final double MM_PER_PAGE = 0.068;
    private static final int TYPICAL_NOVEL_MM = 25;

    private ThicknessEstimator() {
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
