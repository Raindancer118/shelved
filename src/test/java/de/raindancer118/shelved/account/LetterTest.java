package de.raindancer118.shelved.account;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LetterTest {

    private static Letter verify() {
        return Letter.titled("Bestätige deine Adresse")
                .preheader("Ein Klick, dann kannst du dich anmelden.")
                .greeting("Hallo Ada <3,")
                .paragraph("willkommen bei shelved.")
                .action("Adresse bestätigen", "https://shelf.test/#/verify?token=abc&x=1")
                .note("Der Link gilt zwei Tage.");
    }

    @Test
    void htmlHasTheButtonThePreheaderAndEscapesNames() {
        String html = verify().html();
        assertThat(html).contains("href=\"https://shelf.test/#/verify?token=abc&amp;x=1\"");
        assertThat(html).contains("Adresse bestätigen").contains("Ein Klick, dann kannst du dich anmelden.");
        assertThat(html).contains("Hallo Ada &lt;3,").doesNotContain("Ada <3");
        assertThat(html).contains("cid:" + Letter.LOGO_CID);
    }

    @Test
    void plainTextCarriesTheSameMessageAndTheRawLink() {
        String text = verify().text();
        assertThat(text).startsWith("Hallo Ada <3,");
        assertThat(text).contains("willkommen bei shelved.").contains("https://shelf.test/#/verify?token=abc&x=1")
                .contains("Der Link gilt zwei Tage.");
    }

    @Test
    void factsShowAsNumbersInBothForms() {
        Letter l = Letter.titled("Deine Daten").facts(List.of(new Letter.Fact("412", "Bücher"), new Letter.Fact("37", "Wünsche")));
        assertThat(l.html()).contains(">412<").contains(">Bücher<");
        assertThat(l.text()).contains("412 Bücher").contains("37 Wünsche");
    }

    @Test
    void theLogoIsARealPicture() throws Exception {
        var img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(Letter.logoPng()));
        assertThat(img.getWidth()).isEqualTo(img.getHeight()).isGreaterThanOrEqualTo(64);
    }
}
