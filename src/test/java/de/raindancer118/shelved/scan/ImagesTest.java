package de.raindancer118.shelved.scan;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class ImagesTest {

    @Test
    void phonePhotosAreTurnedUprightUsingTheirExifOrientation() throws Exception {
        byte[] jpeg = new ClassPathResource("fixtures/exif-orientation-6.jpg").getContentAsByteArray();

        BufferedImage img = Images.decode(jpeg);

        // stored 400×300 with the red bar on the left; upright it is 300×400 with the bar on top
        assertThat(img.getWidth()).isEqualTo(300);
        assertThat(img.getHeight()).isEqualTo(400);
        Color top = new Color(img.getRGB(150, 10));
        Color bottom = new Color(img.getRGB(150, 390));
        assertThat(top.getRed()).isGreaterThan(200);
        assertThat(top.getGreen()).isLessThan(80);
        assertThat(bottom.getGreen()).isGreaterThan(200);
    }

    @Test
    void imagesWithoutExifStayAsTheyAre() {
        byte[] jpeg = ImageTiler.jpeg(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB));
        BufferedImage img = Images.decode(jpeg);
        assertThat(img.getWidth()).isEqualTo(120);
    }
}
