package de.raindancer118.shelved.scan;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Claude reads images up to 2576 px on the long edge (≈3.75 MP). A whole shelf board shrunk to that
 * makes thin spines unreadable, so wide photos are cut into overlapping segments that keep full height.
 */
public final class ImageTiler {

    static final int MAX_EDGE = 2576;
    static final long MAX_PIXELS = 3_750_000;
    private static final double SEGMENT_ASPECT = 1.5;
    private static final double OVERLAP = 0.12;
    private static final int MAX_SEGMENTS = 4;

    private ImageTiler() {
    }

    public enum Direction { LEFT_TO_RIGHT, TOP_TO_BOTTOM }

    public record Split(List<BufferedImage> images, Direction direction) {
    }

    public static Split split(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        double aspect = (double) w / h;
        if (aspect > 1.8) {
            return new Split(wide(src), Direction.LEFT_TO_RIGHT);
        }
        // a whole bookcase in one 12 MP shot: two overlapping halves along the long side keep spines readable
        if ((long) w * h > 1.5 * MAX_PIXELS) {
            boolean portrait = h > w;
            int len = portrait ? h : w;
            int half = len / 2;
            int overlap = (int) (len * OVERLAP / 2);
            BufferedImage a = portrait ? src.getSubimage(0, 0, w, half + overlap) : src.getSubimage(0, 0, half + overlap, h);
            BufferedImage b = portrait ? src.getSubimage(0, half - overlap, w, h - half + overlap) : src.getSubimage(half - overlap, 0, w - half + overlap, h);
            return new Split(List.of(fit(a), fit(b)), portrait ? Direction.TOP_TO_BOTTOM : Direction.LEFT_TO_RIGHT);
        }
        return new Split(List.of(fit(src)), Direction.LEFT_TO_RIGHT);
    }

    private static List<BufferedImage> wide(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        double aspect = (double) w / h;
        int n = Math.min(MAX_SEGMENTS, (int) Math.ceil(aspect / SEGMENT_ASPECT));
        int step = w / n;
        int overlap = (int) (step * OVERLAP);
        List<BufferedImage> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int x0 = Math.max(0, i * step - overlap);
            int x1 = i == n - 1 ? w : Math.min(w, (i + 1) * step + overlap);
            out.add(fit(src.getSubimage(x0, 0, x1 - x0, h)));
        }
        return out;
    }

    static BufferedImage fit(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        double scale = Math.min(1.0, Math.min((double) MAX_EDGE / Math.max(w, h), Math.sqrt((double) MAX_PIXELS / ((long) w * h))));
        int nw = Math.max(1, (int) Math.floor(w * scale));
        int nh = Math.max(1, (int) Math.floor(h * scale));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    public static byte[] jpeg(BufferedImage img) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.88f);
            writer.setOutput(out);
            writer.write(null, new IIOImage(img, null, null), param);
            writer.dispose();
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
