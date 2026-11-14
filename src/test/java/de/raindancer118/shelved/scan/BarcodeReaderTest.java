package de.raindancer118.shelved.scan;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.oned.EAN13Writer;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class BarcodeReaderTest {

    /** A barcode somewhere on a bigger, slightly noisy photo, like a phone shot of a book's back. */
    static BufferedImage photoWithBarcode(String ean) {
        var matrix = new EAN13Writer().encode(ean, BarcodeFormat.EAN_13, 600, 240);
        BufferedImage img = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xE8E2D4));
        g.fillRect(0, 0, 1600, 1200);
        g.setColor(Color.WHITE);
        g.fillRect(480, 700, 680, 300);
        g.setColor(Color.BLACK);
        for (int x = 0; x < matrix.getWidth(); x++) {
            for (int y = 0; y < matrix.getHeight(); y++) {
                if (matrix.get(x, y)) {
                    g.fillRect(520 + x, 730 + y, 1, 1);
                }
            }
        }
        g.dispose();
        return img;
    }

    @Test
    void readsTheIsbnFromABookBack() {
        assertThat(BarcodeReader.isbn(photoWithBarcode("9783492281980"))).contains("9783492281980");
    }

    @Test
    void ignoresNonBookEans() {
        assertThat(BarcodeReader.isbn(photoWithBarcode("4006381333931"))).isEmpty();
    }

    @Test
    void emptyPictureHasNoIsbn() {
        assertThat(BarcodeReader.isbn(new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB))).isEmpty();
    }
}
