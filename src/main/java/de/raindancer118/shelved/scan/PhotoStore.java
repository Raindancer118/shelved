package de.raindancer118.shelved.scan;

import de.raindancer118.shelved.config.ShelvedProperties;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/** Shelf photos, scaled to 2400 px: enough to recognise every spine in an insurance claim, ~1 MB each. */
@Component
public class PhotoStore {

    static final int MAX_EDGE = 2400;

    private final Path dir;

    public PhotoStore(ShelvedProperties props) {
        this.dir = props.dataDir().resolve("photos");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create photo directory " + dir, e);
        }
    }

    public String save(UUID scanId, BufferedImage img) {
        String name = scanId + ".jpg";
        try (ImageOutputStream out = ImageIO.createImageOutputStream(dir.resolve(name).toFile())) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.setOutput(out);
            writer.write(null, new IIOImage(scaled(img), null, null), param);
            writer.dispose();
            return name;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<Path> path(String file) {
        if (file == null || file.contains("/") || file.contains("..")) {
            return Optional.empty();
        }
        Path p = dir.resolve(file);
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    static BufferedImage scaled(BufferedImage src) {
        double f = Math.min(1.0, (double) MAX_EDGE / Math.max(src.getWidth(), src.getHeight()));
        int w = (int) Math.round(src.getWidth() * f);
        int h = (int) Math.round(src.getHeight() * f);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
