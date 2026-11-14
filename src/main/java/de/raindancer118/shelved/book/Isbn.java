package de.raindancer118.shelved.book;

import java.util.Optional;

public final class Isbn {

    private Isbn() {
    }

    /** Normalizes ISBN-10/13 (with or without hyphens) to a checksum-valid ISBN-13. */
    public static Optional<String> toIsbn13(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.replaceAll("[\\s-]", "").toUpperCase();
        if (s.matches("\\d{13}")) {
            return isValid13(s) ? Optional.of(s) : Optional.empty();
        }
        if (s.matches("\\d{9}[\\dX]") && isValid10(s)) {
            String core = "978" + s.substring(0, 9);
            return Optional.of(core + checkDigit13(core));
        }
        return Optional.empty();
    }

    public static boolean isValid13(String s) {
        return s.matches("\\d{13}") && checkDigit13(s.substring(0, 12)) == s.charAt(12) - '0';
    }

    private static boolean isValid10(String s) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char c = s.charAt(i);
            int v = c == 'X' ? 10 : c - '0';
            if (c == 'X' && i != 9) {
                return false;
            }
            sum += v * (10 - i);
        }
        return sum % 11 == 0;
    }

    private static int checkDigit13(String first12) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (first12.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return (10 - sum % 10) % 10;
    }
}
