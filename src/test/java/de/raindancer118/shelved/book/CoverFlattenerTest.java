package de.raindancer118.shelved.book;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/** Publishers hand in 3D mockups (book at an angle, page block, white background); only the front is the cover. */
class CoverFlattenerTest {

    @Test
    void aMockupLosesBackgroundAndPageBlock() {
        BufferedImage mockup = Mockups.paperback();
        BufferedImage flat = CoverFlattener.flatten(mockup);

        // the white margin and the page block on the right are gone, the dark front fills the picture
        assertThat(flat.getWidth()).isLessThan(mockup.getWidth() - 40);
        assertThat(flat.getHeight()).isLessThan(mockup.getHeight() - 15);
        for (int[] p : new int[][]{{3, 3}, {flat.getWidth() - 4, 3}, {3, flat.getHeight() - 4}, {flat.getWidth() - 4, flat.getHeight() - 4}}) {
            assertThat(brightness(flat.getRGB(p[0], p[1]))).as("corner %s,%s", p[0], p[1]).isLessThan(200);
        }
    }

    /** "Never Be My Date": a white front, so only the outlines show where the front ends and the page block begins. */
    @Test
    void aWhiteMockupIsCutAtItsOutlines() {
        BufferedImage flat = CoverFlattener.flatten(Mockups.whitePaperback());

        assertThat(flat.getWidth()).isBetween(320, 340);
        assertThat(flat.getHeight()).isBetween(515, 536);
    }

    @Test
    void aFlatCoverStaysAsItIs() {
        BufferedImage flat = new BufferedImage(400, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = flat.createGraphics();
        g.setColor(new Color(40, 60, 120));
        g.fillRect(0, 0, 400, 600);
        g.dispose();
        assertThat(CoverFlattener.flatten(flat)).isSameAs(flat);
    }

    @Test
    void aWhiteCoverWithDarkTitleIsNotCut() {
        // white design with a small dark title: white corners, but no book-shaped front to find
        BufferedImage white = new BufferedImage(400, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = white.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 600);
        g.setColor(Color.BLACK);
        g.fillRect(80, 100, 240, 60);
        g.dispose();
        assertThat(CoverFlattener.flatten(white)).isSameAs(white);
    }

    private static int brightness(int rgb) {
        return (((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255)) / 3;
    }
}
