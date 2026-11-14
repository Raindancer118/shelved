package de.raindancer118.shelved.series;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeriesNamesTest {

    @Test
    void variantsOfOneSeriesBecomeOneName() {
        assertThat(SeriesNames.normalize("Eragon-Tetralogie")).isEqualTo("Eragon");
        assertThat(SeriesNames.normalize("Familie Cole-Zyklus")).isEqualTo("Familie Cole");
        assertThat(SeriesNames.normalize("Seven Kingdoms Trilogy")).isEqualTo("Seven Kingdoms");
        assertThat(SeriesNames.normalize("Die Magic Academy-Reihe")).isEqualTo("Die Magic Academy");
        assertThat(SeriesNames.normalize("Scheibenwelt-Romane")).isEqualTo("Scheibenwelt");
        assertThat(SeriesNames.normalize("Die Tribute von Panem (Romanreihe)")).isEqualTo("Die Tribute von Panem");
    }

    @Test
    void realNamesStay() {
        assertThat(SeriesNames.normalize("Bis(s)")).isEqualTo("Bis(s)");
        assertThat(SeriesNames.normalize("Die Chroniken von Narnia")).isEqualTo("Die Chroniken von Narnia");
        assertThat(SeriesNames.normalize("Saga")).isEqualTo("Saga");
    }
}
