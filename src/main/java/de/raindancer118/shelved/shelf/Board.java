package de.raindancer118.shelved.shelf;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class Board {

    /** Board widths unknown at creation start with this until a photo or a tape measure says otherwise. */
    public static final int PROVISIONAL_MM = 800;

    @Column(name = "width_mm", nullable = false)
    private int widthMm;
    @Column(nullable = false)
    private boolean estimated;

    protected Board() {
    }

    public Board(int widthMm, boolean estimated) {
        this.widthMm = widthMm;
        this.estimated = estimated;
    }

    public static Board unknown() {
        return new Board(PROVISIONAL_MM, true);
    }

    public int getWidthMm() { return widthMm; }
    public boolean isEstimated() { return estimated; }
}
