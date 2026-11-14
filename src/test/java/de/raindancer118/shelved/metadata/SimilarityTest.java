package de.raindancer118.shelved.metadata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SimilarityTest {

    @Test
    void identicalTitlesMatchFully() {
        assertThat(Similarity.title("Die Farben der Magie", "Farben der Magie")).isEqualTo(1.0);
    }

    @Test
    void spineTitleWithSubtitleMatchesCatalogueTitlePlusSubtitle() {
        assertThat(Similarity.title("Mysteria – Das Tor des Feuers", "Mysteria", "das Tor des Feuers")).isEqualTo(1.0);
    }

    @Test
    void shortSpineTitleInsideALongCatalogueTitleStillCounts() {
        assertThat(Similarity.title("Die Queen und ich", "Die Queen und ich - aus dem Leben eines königlichen Corgis", "Roman"))
                .isGreaterThanOrEqualTo(0.7);
    }

    @Test
    void differentBooksStayApart() {
        assertThat(Similarity.title("Der Schwarm", "Laura und der Ring der Feuerschlange", "Roman")).isLessThan(0.3);
    }

    @Test
    void mainTitleIsThePartBeforeTheDash() {
        assertThat(Similarity.mainTitle("Sinkalion – Das Schwert des Schicksals")).isEqualTo("Sinkalion");
        assertThat(Similarity.mainTitle("Momo")).isEqualTo("Momo");
    }
}
