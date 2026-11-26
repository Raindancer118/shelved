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
        assertThat(thick).isBetween(36, 42);
    }

    /** Fourth Wing, dtv hardcover, 768 pages: 217 x 145 x 54 mm as the trade lists it. */
    @Test
    void matchesAPublishedHardcover() {
        assertThat(ThicknessEstimator.estimateMm(null, 768, BookFormat.HARDCOVER)).isBetween(52, 56);
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
