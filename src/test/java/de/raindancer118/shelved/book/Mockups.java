package de.raindancer118.shelved.book;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Drawn like the 3D mockups publishers hand in: white ground, slightly slanted front, a gap, the page block's outline. */
final class Mockups {

    private Mockups() {
    }

    static BufferedImage paperback() {
        BufferedImage img = new BufferedImage(398, 599, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 398, 599);
        // page block behind the front, light with a grey outline on the right
        g.setColor(new Color(238, 238, 236));
        g.fillPolygon(new int[]{40, 372, 380, 48}, new int[]{8, 4, 575, 582}, 4);
        g.setColor(new Color(120, 120, 120));
        g.setStroke(new BasicStroke(2));
        g.drawLine(376, 6, 380, 575);
        // the front, a hair off square like a book seen slightly from the side
        g.setColor(new Color(30, 28, 60));
        g.fillPolygon(new int[]{10, 356, 360, 9}, new int[]{12, 9, 584, 588}, 4);
        g.setColor(new Color(200, 170, 90));
        g.fillRect(90, 200, 180, 120);
        g.dispose();
        return img;
    }

    /**
     * A special edition: hardcover front over the full height, right of it the slightly shorter page block in a
     * dark sprayed edge with orange stripes, the way publishers show a Farbschnitt.
     */
    static BufferedImage sprayedHardcover() {
        BufferedImage img = new BufferedImage(316, 436, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 316, 436);
        g.setColor(new Color(20, 30, 60)); // the Farbschnitt, 9 px shorter top and bottom
        g.fillPolygon(new int[]{258, 300, 300, 258}, new int[]{18, 20, 412, 415}, 4);
        g.setColor(new Color(230, 120, 30));
        for (int y = 48; y < 408; y += 40) {
            g.fillRect(258, y, 42, 8);
        }
        g.setColor(new Color(225, 205, 160)); // the back board's edge beyond the pages
        g.fillRect(300, 14, 5, 404);
        g.setColor(new Color(225, 200, 140)); // the front: light gold with a dark title
        g.fillRect(12, 9, 248, 418);
        g.setColor(new Color(40, 30, 20));
        g.fillRect(48, 148, 170, 60);
        g.dispose();
        return img;
    }

    /** A white cover: hardly any colour on the front, only thin outlines tell front and page block apart. */
    static BufferedImage whitePaperback() {
        BufferedImage img = new BufferedImage(398, 599, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 398, 599);
        g.setColor(new Color(232, 232, 230)); // page block, above and right of the front
        g.fillRect(40, 10, 336, 28);
        g.fillRect(348, 10, 28, 565);
        g.setColor(new Color(100, 100, 100));
        g.drawLine(40, 10, 375, 10);       // outer outline of the page block
        g.drawLine(375, 10, 375, 574);
        g.setColor(new Color(170, 170, 170));
        g.drawRect(8, 38, 339, 536);       // the front's own outline
        g.setColor(new Color(120, 90, 160)); // a title, the only colour on the front
        g.fillRect(60, 120, 230, 60);
        g.dispose();
        return img;
    }

    static byte[] paperbackJpeg() {
        try {
            var out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(paperback(), "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
