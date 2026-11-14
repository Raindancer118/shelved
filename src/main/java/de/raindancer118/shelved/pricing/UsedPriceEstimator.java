package de.raindancer118.shelved.pricing;

import de.raindancer118.shelved.book.BookFormat;

/**
 * Rule of thumb for what a read book still brings, calibrated against the German second-hand market (2026):
 * asking prices on Kleinanzeigen halve after one reading, actual sales and Booklooker's commission take more off,
 * and buyers like momox pay 20–40 % of that, nothing for old mass-market paperbacks.
 * No market data per title: that would need scraping the marketplaces, which their terms don't allow.
 */
public final class UsedPriceEstimator {

    /** Private sale (Kleinanzeigen, Booklooker) and what a buyback portal pays. */
    public record UsedValue(int privateCents, int buybackCents) {
    }

    private UsedPriceEstimator() {
    }

    public static UsedValue estimate(Integer listCents, BookFormat format, Integer publishedYear, int currentYear) {
        if (listCents == null || listCents <= 0) {
            return null;
        }
        boolean hardcover = format == BookFormat.HARDCOVER;
        int age = publishedYear == null ? 6 : Math.max(0, currentYear - publishedYear);
        double share;
        double buyback;
        if (age <= 1) {
            share = hardcover ? 0.35 : 0.30;
            buyback = 0.40;
        } else if (age <= 4) {
            share = hardcover ? 0.25 : 0.20;
            buyback = 0.30;
        } else if (age <= 10) {
            share = hardcover ? 0.18 : 0.15;
            buyback = 0.20;
        } else {
            share = hardcover ? 0.10 : 0.06;
            buyback = hardcover ? 0.10 : 0.0;
        }
        int privateCents = Math.max(50, (int) Math.round(listCents * share / 50.0) * 50);
        int buybackCents = (int) (privateCents * buyback / 5) * 5;
        return new UsedValue(privateCents, buybackCents < 10 ? 0 : buybackCents);
    }

    public static Integer privateCents(Integer listCents, BookFormat format, Integer publishedYear, int currentYear) {
        UsedValue v = estimate(listCents, format, publishedYear, currentYear);
        return v == null ? null : v.privateCents();
    }

    public static Integer buybackCents(Integer listCents, BookFormat format, Integer publishedYear, int currentYear) {
        UsedValue v = estimate(listCents, format, publishedYear, currentYear);
        return v == null ? null : v.buybackCents();
    }
}
