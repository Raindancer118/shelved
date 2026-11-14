package de.raindancer118.shelved.scan;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import de.raindancer118.shelved.book.Isbn;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Server-side barcode decoding for browsers without the BarcodeDetector API (iPhone, Firefox). */
public final class BarcodeReader {

    private static final Map<DecodeHintType, Object> HINTS = Map.of(
            DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.EAN_13),
            DecodeHintType.TRY_HARDER, Boolean.TRUE);

    private BarcodeReader() {
    }

    public static Optional<String> isbn(BufferedImage image) {
        BufferedImage img = ImageTiler.fit(image);
        try {
            var result = new MultiFormatReader().decode(
                    new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img))), HINTS);
            String text = result.getText();
            if (!text.startsWith("978") && !text.startsWith("979")) {
                return Optional.empty();
            }
            return Isbn.toIsbn13(text);
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }
}
