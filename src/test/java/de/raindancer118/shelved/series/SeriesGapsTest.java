package de.raindancer118.shelved.series;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SeriesGapsTest {

    @Test
    void withoutCatalogOnlyHolesBetweenOwnedVolumesCount() {
        var missing = SeriesGaps.missing(Set.of(1, 2, 4, 8), List.of());
        assertThat(missing).extracting(SeriesGaps.Missing::number).containsExactly(3, 5, 6, 7);
        assertThat(missing).allMatch(m -> m.title() == null);
    }

    @Test
    void catalogAddsTitlesAndVolumesAfterTheLastOwnedOne() {
        var catalog = List.of(
                new SeriesCatalogVolume(1, "Die Farben der Magie"),
                new SeriesCatalogVolume(2, "Das Licht der Phantasie"),
                new SeriesCatalogVolume(3, "Das Erbe des Zauberers"),
                new SeriesCatalogVolume(4, "Gevatter Tod"),
                new SeriesCatalogVolume(5, "Der Zauberhut"));
        var missing = SeriesGaps.missing(Set.of(1, 2, 4), catalog);
        assertThat(missing).extracting(m -> m.number() + ":" + m.title())
                .containsExactly("3:Das Erbe des Zauberers", "5:Der Zauberhut");
    }

    @Test
    void nothingOwnedWithNumbersMeansNoGaps() {
        assertThat(SeriesGaps.missing(Set.of(), List.of())).isEmpty();
    }
}
