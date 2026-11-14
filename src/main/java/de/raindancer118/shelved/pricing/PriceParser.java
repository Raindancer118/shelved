package de.raindancer118.shelved.pricing;

import de.raindancer118.shelved.book.BookFormat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads DNB/VLB price notes like "kart. : EUR 9.99 (DE), EUR 10.30 (AT), sfr 14.90 (freier Pr.)". */
public final class PriceParser {

    private static final Pattern EUR = Pattern.compile("EUR\\s*(\\d+)[.,](\\d{2})\\s*(\\((DE|AT)\\))?\\s*(\\(freier[^)]*\\))?");

    private PriceParser() {
    }

    public static Optional<Integer> germanEuroCents(String note) {
        if (note == null) {
            return Optional.empty();
        }
        Matcher m = EUR.matcher(note);
        Integer fallback = null;
        while (m.find()) {
            int cents = Integer.parseInt(m.group(1)) * 100 + Integer.parseInt(m.group(2));
            boolean free = m.group(5) != null;
            String country = m.group(4);
            if ("DE".equals(country) && !free) {
                return Optional.of(cents);
            }
            if (fallback == null && (country == null || "DE".equals(country))) {
                fallback = cents;
            }
        }
        return Optional.ofNullable(fallback);
    }

    public static Optional<BookFormat> format(String note) {
        if (note == null) {
            return Optional.empty();
        }
        String q = note.toLowerCase();
        int colon = q.indexOf(':');
        String qualifier = colon >= 0 ? q.substring(0, colon) : q;
        if (qualifier.contains("kart") || qualifier.contains("brosch") || qualifier.contains("paperback") || qualifier.contains("tb")) {
            return Optional.of(BookFormat.PAPERBACK);
        }
        if (qualifier.contains("pp") || qualifier.contains("gb") || qualifier.contains("geb") || qualifier.contains("hardcover") || qualifier.contains("ln")) {
            return Optional.of(BookFormat.HARDCOVER);
        }
        return Optional.empty();
    }
}
