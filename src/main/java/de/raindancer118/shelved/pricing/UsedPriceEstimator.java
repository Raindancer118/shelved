package de.raindancer118.shelved.pricing;

import de.raindancer118.shelved.book.BookFormat;

/**
 * Rule of thumb for the second-hand value, used when no market data is available:
 * paperbacks keep about 30 %, hardcovers about 40 %, books from the last two years a bit more.
 * Rounded to 50 cents, never below 1 €.
 */
public final class UsedPriceEstimator {

    private UsedPriceEstimator() {
    }

    public static Integer estimateCents(Integer listCents, BookFormat format, Integer publishedYear, int currentYear) {
        if (listCents == null || listCents <= 0) {
            return null;
        }
        boolean recent = publishedYear != null && currentYear - publishedYear <= 2;
        double share = format == BookFormat.HARDCOVER ? 0.40 : 0.30;
        if (recent) {
            share += 0.15;
        }
        int cents = (int) Math.round(listCents * share / 50.0) * 50;
        return Math.max(100, cents);
    }
}
