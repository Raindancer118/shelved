package de.raindancer118.shelved.book;

import de.raindancer118.shelved.ai.LibraryAi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * A photo of a back, spine or fore edge shows the face at an angle with the room around it. Claude finds the four
 * corners, ImageWarp cuts the face out straight. Whenever that fails or looks wrong, the photo is kept as taken.
 */
@Component
public class FacePhotos {

    private static final Logger log = LoggerFactory.getLogger(FacePhotos.class);

    public record Picture(byte[] bytes, String ext) {
    }

    private final LibraryAi ai;

    public FacePhotos(LibraryAi ai) {
        this.ai = ai;
    }

    /**
     * @param frontAspect width / height of the front cover, if known: a back is the same size as the front, while a
     *                    photo taken at a steep angle doesn't tell its true proportions
     */
    public Picture straighten(byte[] image, String ext, Book.Side side, Double frontAspect) {
        if (!ai.available()) {
            return new Picture(image, ext);
        }
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(image));
            if (src == null) {
                return new Picture(image, ext);
            }
            LibraryAi.FaceLocation at = ai.locateFace(image, side.face);
            if (at == null || !at.found()) {
                return new Picture(image, ext);
            }
            double[][] rough = quad(at, src.getWidth(), src.getHeight());
            double[][] exact = ImageWarp.refine(src, rough, 0.03);
            log.debug("{} corners: model {} refined {}", side.face, java.util.Arrays.deepToString(rough), java.util.Arrays.deepToString(exact));
            BufferedImage face = ImageWarp.straighten(src, inset(exact));
            double aspect = (double) face.getWidth() / face.getHeight();
            boolean plausible = side == Book.Side.BACK ? aspect > 0.3 && aspect < 1.0 : aspect < 0.5;
            if (!plausible) {
                log.info("Straightened {} has aspect {}, keeping the photo as taken", side.face, aspect);
                return new Picture(image, ext);
            }
            if (side == Book.Side.BACK && frontAspect != null) {
                face = resize(face, (int) Math.round(face.getHeight() * frontAspect), face.getHeight());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(face, "jpg", out);
            return new Picture(out.toByteArray(), "jpg");
        } catch (IOException | RuntimeException e) {
            log.warn("Could not straighten a {} photo: {}", side.face, e.getMessage());
            return new Picture(image, ext);
        }
    }

    private static BufferedImage resize(BufferedImage img, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** A hair inside the outline, so the cut never carries a seam of background. */
    private static double[][] inset(double[][] q) {
        double cx = 0, cy = 0;
        for (double[] p : q) {
            cx += p[0] / 4;
            cy += p[1] / 4;
        }
        double[][] out = new double[4][];
        for (int i = 0; i < 4; i++) {
            out[i] = new double[]{q[i][0] + (cx - q[i][0]) * 0.012, q[i][1] + (cy - q[i][1]) * 0.012};
        }
        return out;
    }

    private static double[][] quad(LibraryAi.FaceLocation at, int w, int h) {
        LibraryAi.Corner[] cs = {at.headLeft(), at.headRight(), at.tailRight(), at.tailLeft()};
        double[][] q = new double[4][];
        for (int i = 0; i < 4; i++) {
            q[i] = new double[]{Math.clamp(cs[i].x(), 0, 1) * w, Math.clamp(cs[i].y(), 0, 1) * h};
        }
        return q;
    }
}
