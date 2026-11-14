package de.raindancer118.shelved.pricing;

import de.raindancer118.shelved.book.BookFormat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsedPriceEstimatorTest {

    @Test
    void paperbackKeepsAboutAThird() {
        assertThat(UsedPriceEstimator.estimateCents(1200, BookFormat.PAPERBACK, 2019, 2026)).isBetween(300, 450);
    }

    @Test
    void neverBelowOneEuroAndNoGuessWithoutListPrice() {
        assertThat(UsedPriceEstimator.estimateCents(250, BookFormat.PAPERBACK, 1990, 2026)).isEqualTo(100);
        assertThat(UsedPriceEstimator.estimateCents(null, BookFormat.PAPERBACK, 1990, 2026)).isNull();
    }

    @Test
    void hardcoverHoldsValueBetter() {
        assertThat(UsedPriceEstimator.estimateCents(2400, BookFormat.HARDCOVER, 2024, 2026))
                .isGreaterThan(UsedPriceEstimator.estimateCents(2400, BookFormat.PAPERBACK, 2024, 2026));
    }
}
