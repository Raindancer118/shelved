package de.raindancer118.shelved.placement;

import de.raindancer118.shelved.book.BookFormat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThicknessEstimatorTest {

    @Test
    void explicitThicknessWins() {
        assertThat(ThicknessEstimator.estimateMm(31, 300, BookFormat.PAPERBACK)).isEqualTo(31);
    }

    @Test
    void paperbackGrowsWithPages() {
        int thin = ThicknessEstimator.estimateMm(null, 120, BookFormat.PAPERBACK);
        int thick = ThicknessEstimator.estimateMm(null, 600, BookFormat.PAPERBACK);
        assertThat(thin).isBetween(9, 14);
        assertThat(thick).isBetween(55, 66);
    }

    /** Calibrated on a real shelf: 14 German YA/fantasy books, 6330 pages, about 68 cm on the board. */
    @Test
    void matchesARealShelfOfGermanTradeBooks() {
        int total = 2 * ThicknessEstimator.estimateMm(null, null, null); // two books without page count
        for (int pages : new int[]{795, 941, 282, 448, 507, 401, 477, 475}) {
            total += ThicknessEstimator.estimateMm(null, pages, BookFormat.PAPERBACK);
        }
        for (int pages : new int[]{494, 540, 482, 488}) {
            total += ThicknessEstimator.estimateMm(null, pages, BookFormat.HARDCOVER);
        }
        assertThat(total).isBetween(660, 760);
    }

    @Test
    void hardcoverAddsBoardThickness() {
        assertThat(ThicknessEstimator.estimateMm(null, 300, BookFormat.HARDCOVER))
                .isGreaterThan(ThicknessEstimator.estimateMm(null, 300, BookFormat.PAPERBACK) + 3);
    }

    @Test
    void unknownPagesFallBackToTypicalNovel() {
        assertThat(ThicknessEstimator.estimateMm(null, null, null)).isEqualTo(25);
    }
}
