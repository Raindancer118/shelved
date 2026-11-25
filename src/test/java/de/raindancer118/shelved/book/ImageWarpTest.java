package de.raindancer118.shelved.book;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class ImageWarpTest {

    /** A book's back photographed at an angle: a skewed red quad with a blue top band on grey. */
    private static BufferedImage photo() {
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.GRAY);
        g.fillRect(0, 0, 400, 300);
        g.setColor(Color.RED);
        g.fillPolygon(new Polygon(new int[]{120, 300, 320, 90}, new int[]{40, 60, 270, 250}, 4));
        g.setColor(Color.BLUE); // the top fifth of the quad
        g.fillPolygon(new Polygon(new int[]{120, 300, 304, 114}, new int[]{40, 60, 102, 82}, 4));
        g.dispose();
        return img;
    }

    @Test
    void cutsTheQuadOutStraightAndFillsTheResultWithIt() {
        BufferedImage out = ImageWarp.straighten(photo(), new double[][]{{120, 40}, {300, 60}, {320, 270}, {90, 250}});
        assertThat(out.getHeight()).isGreaterThan(out.getWidth());
        assertThat(out.getWidth()).isBetween(170, 230);
        assertThat(out.getHeight()).isBetween(190, 230);
        int grey = 0;
        for (int y = 2; y < out.getHeight() - 2; y += 4) {
            for (int x = 2; x < out.getWidth() - 2; x += 4) {
                Color c = new Color(out.getRGB(x, y));
                grey += c.getRed() == c.getGreen() && c.getGreen() == c.getBlue() ? 1 : 0;
            }
        }
        assertThat(grey).as("background pixels inside the result").isLessThan(10);
        assertThat(new Color(out.getRGB(out.getWidth() / 2, out.getHeight() / 20)).getBlue()).as("top stays on top").isGreaterThan(200);
        assertThat(new Color(out.getRGB(out.getWidth() / 2, out.getHeight() * 3 / 4)).getRed()).isGreaterThan(200);
    }

    @Test
    void roughCornersSnapToTheEdgesOfTheFace() {
        double[][] rough = {{108, 52}, {311, 49}, {309, 281}, {101, 241}}; // each corner 10–15 px off
        double[][] q = ImageWarp.refine(photo(), rough, 0.06);
        double[][] truth = {{120, 40}, {300, 60}, {320, 270}, {90, 250}};
        for (int i = 0; i < 4; i++) {
            assertThat(Math.hypot(q[i][0] - truth[i][0], q[i][1] - truth[i][1])).as("corner " + i).isLessThan(4);
        }
    }
}
