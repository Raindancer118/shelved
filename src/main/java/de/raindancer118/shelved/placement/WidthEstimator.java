package de.raindancer118.shelved.placement;

import java.util.Optional;

/**
 * Board width from a photo: the books on it have a known (estimated) total thickness, and the photo shows
 * what fraction of the board they fill. Only when both side walls are visible, otherwise the fill is a guess.
 */
public final class WidthEstimator {

    private static final double MIN_FILL = 0.2;
    private static final int MIN_MM = 150;
    private static final int MAX_MM = 2500;

    private WidthEstimator() {
    }

    public static Optional<Integer> boardWidthMm(int booksMm, double fill, boolean bothEndsVisible) {
        if (!bothEndsVisible || booksMm <= 0 || fill < MIN_FILL) {
            return Optional.empty();
        }
        int mm = (int) Math.round(booksMm / Math.min(fill, 1.0) / 10.0) * 10;
        return mm >= MIN_MM && mm <= MAX_MM ? Optional.of(mm) : Optional.empty();
    }
}
