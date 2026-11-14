package de.raindancer118.shelved.scan;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Decoding uploads. Phones store photos sideways plus an EXIF hint; ImageIO ignores the hint, so we apply it. */
public final class Images {

    private static final long MAX_PIXELS = 60_000_000L;

    private Images() {
    }

    public static BufferedImage decode(byte[] bytes) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new IllegalArgumentException("Bild konnte nicht gelesen werden");
        }
        if (img == null) {
            throw new IllegalArgumentException("Bildformat nicht erkannt (JPEG oder PNG)");
        }
        if ((long) img.getWidth() * img.getHeight() > MAX_PIXELS) {
            throw new IllegalArgumentException("Bild ist zu groß");
        }
        return upright(img, orientation(bytes));
    }

    static int orientation(byte[] bytes) {
        try {
            var dir = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes)).getFirstDirectoryOfType(ExifIFD0Directory.class);
            return dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION) ? dir.getInt(ExifIFD0Directory.TAG_ORIENTATION) : 1;
        } catch (Exception e) {
            return 1; // no or broken metadata: take the pixels as they are
        }
    }

    static BufferedImage upright(BufferedImage img, int orientation) {
        int w = img.getWidth();
        int h = img.getHeight();
        AffineTransform t = new AffineTransform();
        boolean swap = false;
        switch (orientation) {
            case 2 -> { t.scale(-1, 1); t.translate(-w, 0); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.scale(1, -1); t.translate(0, -h); }
            case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); swap = true; }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); swap = true; }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); swap = true; }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); swap = true; }
            default -> { return img; }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, t, null);
        g.dispose();
        return out;
    }
}
