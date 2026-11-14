package de.raindancer118.shelved.scan;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class ImageTilerTest {

    @Test
    void moderatePhotoStaysOneTileWithinTheModelLimit() {
        var tiles = ImageTiler.split(new BufferedImage(2200, 1650, BufferedImage.TYPE_INT_RGB)).images();
        assertThat(tiles).hasSize(1);
        assertThat(Math.max(tiles.getFirst().getWidth(), tiles.getFirst().getHeight())).isLessThanOrEqualTo(ImageTiler.MAX_EDGE);
        assertThat((long) tiles.getFirst().getWidth() * tiles.getFirst().getHeight()).isLessThanOrEqualTo(ImageTiler.MAX_PIXELS);
    }

    /** A 12 MP phone photo of a whole bookcase: halves keep about twice the detail of one shrunk image. */
    @Test
    void bigPortraitPhotoIsSplitIntoOverlappingTopAndBottomHalves() {
        var split = ImageTiler.split(new BufferedImage(3000, 4000, BufferedImage.TYPE_INT_RGB));
        assertThat(split.direction()).isEqualTo(ImageTiler.Direction.TOP_TO_BOTTOM);
        assertThat(split.images()).hasSize(2);
        assertThat(split.images()).allSatisfy(t -> {
            assertThat(Math.max(t.getWidth(), t.getHeight())).isLessThanOrEqualTo(ImageTiler.MAX_EDGE);
            assertThat((long) t.getWidth() * t.getHeight()).isLessThanOrEqualTo(ImageTiler.MAX_PIXELS);
        });
    }

    @Test
    void bigLandscapePhotoIsSplitLeftAndRight() {
        var split = ImageTiler.split(new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB));
        assertThat(split.direction()).isEqualTo(ImageTiler.Direction.LEFT_TO_RIGHT);
        assertThat(split.images()).hasSize(2);
    }

    @Test
    void smallPhotoIsNotUpscaled() {
        var tiles = ImageTiler.split(new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB)).images();
        assertThat(tiles.getFirst().getWidth()).isEqualTo(1600);
    }

    @Test
    void wideShelfPhotoIsSplitIntoOverlappingSegments() {
        var tiles = ImageTiler.split(new BufferedImage(6000, 1500, BufferedImage.TYPE_INT_RGB)).images();
        assertThat(tiles).hasSize(3);
        // every segment keeps the full height so spines stay readable
        assertThat(tiles).allSatisfy(t -> assertThat(t.getHeight()).isEqualTo(1500));
        int covered = tiles.stream().mapToInt(BufferedImage::getWidth).sum();
        assertThat(covered).isGreaterThan(6000); // overlap
    }

    @Test
    void panoramaIsCappedAtFourSegments() {
        assertThat(ImageTiler.split(new BufferedImage(20000, 1000, BufferedImage.TYPE_INT_RGB)).images()).hasSize(4);
    }

    @Test
    void encodesJpeg() {
        byte[] jpeg = ImageTiler.jpeg(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB));
        assertThat(jpeg[0]).isEqualTo((byte) 0xFF);
        assertThat(jpeg[1]).isEqualTo((byte) 0xD8);
    }
}
