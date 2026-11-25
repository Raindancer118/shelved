package de.raindancer118.shelved.metadata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextsTest {

    @Test
    void entitiesAndTagsAreGone() {
        assertThat(Texts.plain("<p>Logen&#160;kämpft &amp; siegt.</p>")).isEqualTo("Logen\u00a0kämpft & siegt.");
    }

    /** A whole web page (a publisher's home page linked as "blurb") is never shown as a blurb. */
    @Test
    void aWebPageIsNoBlurb() {
        String page = String.join("\n", java.util.Collections.nCopies(80, "Kinderbuch\nAlle Kinderbücher\nMerkzettel"));
        assertThat(Texts.blurb(page)).isNull();
        assertThat(Texts.blurb("Für Logen, den Barbarenkrieger, sind die Zeiten siegreicher Schlachten vorbei."))
                .isEqualTo("Für Logen, den Barbarenkrieger, sind die Zeiten siegreicher Schlachten vorbei.");
        assertThat(Texts.blurb("   ")).isNull();
    }
}
