package de.raindancer118.shelved.placement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WidthEstimatorTest {

    @Test
    void booksFillingHalfTheBoardMeanTheBoardIsTwiceAsWide() {
        assertThat(WidthEstimator.boardWidthMm(380, 0.5, true)).contains(760);
    }

    @Test
    void roundsToCentimetres() {
        assertThat(WidthEstimator.boardWidthMm(301, 0.8, true)).contains(380);
    }

    @Test
    void noEstimateWithoutBothSideWallsOrWithTooFewBooks() {
        assertThat(WidthEstimator.boardWidthMm(400, 0.5, false)).isEmpty();
        assertThat(WidthEstimator.boardWidthMm(60, 0.1, true)).isEmpty();
        assertThat(WidthEstimator.boardWidthMm(0, 0.9, true)).isEmpty();
    }

    @Test
    void implausibleResultsAreDropped() {
        assertThat(WidthEstimator.boardWidthMm(3000, 0.5, true)).isEmpty(); // 6 m board
        assertThat(WidthEstimator.boardWidthMm(100, 1.0, true)).isEmpty();  // 10 cm board
    }

    @Test
    void anOverfullReadingCountsAsFull() {
        assertThat(WidthEstimator.boardWidthMm(760, 1.3, true)).contains(760);
    }
}
