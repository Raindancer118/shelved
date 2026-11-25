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
