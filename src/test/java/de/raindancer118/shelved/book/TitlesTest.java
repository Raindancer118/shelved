package de.raindancer118.shelved.book;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TitlesTest {

    @Test
    void stripsMarcNonSortingMarkersForDisplay() {
        assertThat(Titles.display("\u0098Die\u009C Farben der Magie")).isEqualTo("Die Farben der Magie");
    }

    @Test
    void sortTitleDropsTheMarkedArticle() {
        assertThat(Titles.sortForm("\u0098Die\u009C Farben der Magie")).isEqualTo("Farben der Magie");
    }

    @Test
    void sortTitleDropsCommonLeadingArticles() {
        assertThat(Titles.sortForm("Der goldene Kompass")).isEqualTo("goldene Kompass");
        assertThat(Titles.sortForm("The Colour of Magic")).isEqualTo("Colour of Magic");
        assertThat(Titles.sortForm("Gevatter Tod")).isEqualTo("Gevatter Tod");
    }
}
