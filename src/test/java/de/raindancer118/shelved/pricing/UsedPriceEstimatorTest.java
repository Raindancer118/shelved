package de.raindancer118.shelved.pricing;

import de.raindancer118.shelved.book.BookFormat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsedPriceEstimatorTest {

    private static UsedPriceEstimator.UsedValue value(int listCents, BookFormat format, int year) {
        return UsedPriceEstimator.estimate(listCents, format, year, 2026);
    }

    @Test
    void aFewYearsOldPaperbackSellsPrivatelyForAboutTwoEuros() {
        // 12 € Taschenbuch von 2019: Kleinanzeigen/Booklooker realistisch 1,50–2,50 €
        assertThat(value(1200, BookFormat.PAPERBACK, 2019).privateCents()).isBetween(150, 250);
    }

    @Test
    void aBuyerPaysOnlyAFractionOfThePrivatePrice() {
        UsedPriceEstimator.UsedValue v = value(2200, BookFormat.HARDCOVER, 2025);
        assertThat(v.buybackCents()).isLessThan(v.privateCents() / 2);
        assertThat(v.buybackCents()).isPositive();
    }

    @Test
    void oldMassMarketPaperbacksAreWorthNextToNothingAtABuyer() {
        UsedPriceEstimator.UsedValue v = value(899, BookFormat.PAPERBACK, 2005);
        assertThat(v.privateCents()).isEqualTo(50);
        assertThat(v.buybackCents()).isZero();
    }

    @Test
    void newHardcoversHoldTheirValueBest() {
        assertThat(value(2400, BookFormat.HARDCOVER, 2026).privateCents())
                .isGreaterThan(value(2400, BookFormat.PAPERBACK, 2026).privateCents())
                .isGreaterThan(value(2400, BookFormat.HARDCOVER, 2018).privateCents())
                .isBetween(600, 900);
    }

    @Test
    void aTypicalShelfOfFiftyBooksIsWorthAFewHundredAtMost() {
        // Toms Testregal: 54 Bücher, Ø ~14 € Neupreis, überwiegend Taschenbuch, Ø ~6 Jahre alt
        long sum = 0;
        for (int i = 0; i < 54; i++) {
            sum += value(i % 3 == 0 ? 2000 : 1100, i % 3 == 0 ? BookFormat.HARDCOVER : BookFormat.PAPERBACK, 2016 + i % 10).privateCents();
        }
        assertThat(sum).isBetween(8_000L, 20_000L);
    }

    @Test
    void noGuessWithoutListPrice() {
        assertThat(UsedPriceEstimator.estimate(null, BookFormat.PAPERBACK, 1990, 2026)).isNull();
    }
}
