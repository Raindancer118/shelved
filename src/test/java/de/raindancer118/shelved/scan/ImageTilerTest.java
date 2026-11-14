package de.raindancer118.shelved.scan;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class ImageTilerTest {

    @Test
    void normalPhotoStaysOneTileAndIsScaledToTheModelLimit() {
        var tiles = ImageTiler.tiles(new BufferedImage(4032, 3024, BufferedImage.TYPE_INT_RGB));
        assertThat(tiles).hasSize(1);
        assertThat(Math.max(tiles.getFirst().getWidth(), tiles.getFirst().getHeight())).isLessThanOrEqualTo(ImageTiler.MAX_EDGE);
        assertThat((long) tiles.getFirst().getWidth() * tiles.getFirst().getHeight()).isLessThanOrEqualTo(ImageTiler.MAX_PIXELS);
    }

    @Test
    void smallPhotoIsNotUpscaled() {
        var tiles = ImageTiler.tiles(new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB));
        assertThat(tiles.getFirst().getWidth()).isEqualTo(1600);
    }

    @Test
    void wideShelfPhotoIsSplitIntoOverlappingSegments() {
        var tiles = ImageTiler.tiles(new BufferedImage(6000, 1500, BufferedImage.TYPE_INT_RGB));
        assertThat(tiles).hasSize(3);
        // every segment keeps the full height so spines stay readable
        assertThat(tiles).allSatisfy(t -> assertThat(t.getHeight()).isEqualTo(1500));
        int covered = tiles.stream().mapToInt(BufferedImage::getWidth).sum();
        assertThat(covered).isGreaterThan(6000); // overlap
    }

    @Test
    void panoramaIsCappedAtFourSegments() {
        assertThat(ImageTiler.tiles(new BufferedImage(20000, 1000, BufferedImage.TYPE_INT_RGB))).hasSize(4);
    }

    @Test
    void encodesJpeg() {
        byte[] jpeg = ImageTiler.jpeg(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB));
        assertThat(jpeg[0]).isEqualTo((byte) 0xFF);
        assertThat(jpeg[1]).isEqualTo((byte) 0xD8);
    }
}
