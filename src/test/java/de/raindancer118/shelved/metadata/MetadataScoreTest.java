package de.raindancer118.shelved.metadata;

import de.raindancer118.shelved.book.BookFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataScoreTest {

    static BookMetadata dnb(String publisher, String language, Integer price) {
        return new BookMetadata("9783000000000", "Momo", "Momo", null, List.of("Michael Ende"), "Ende, Michael", publisher,
                2025, 300, null, BookFormat.PAPERBACK, language, price, null, null, null, "DNB");
    }

    @Test
    void theRegularGermanTradeEditionBeatsLearnerAndForeignEditions() {
        double trade = MetadataService.score(dnb("Thienemann", "ger", 1400), "Momo", "Michael Ende", null);
        double learner = MetadataService.score(dnb("Ernst Klett Sprachen", "ger", 1100), "Momo", "Michael Ende", null);
        double foreign = MetadataService.score(dnb("Editorial Presença", "por", 1690), "Momo", "Michael Ende", null);
        double pod = MetadataService.score(dnb("BoD – Books on Demand", "ger", 1100), "Momo", "Michael Ende", null);
        assertThat(trade).isGreaterThan(learner).isGreaterThan(foreign).isGreaterThan(pod);
    }

    @Test
    void thePublisherSeenOnTheSpineWins() {
        double seen = MetadataService.score(dnb("Ernst Klett Sprachen", "ger", 1100), "Momo", "Michael Ende", "Klett");
        double other = MetadataService.score(dnb("Thienemann", "ger", 1400), "Momo", "Michael Ende", "Klett");
        assertThat(seen).isGreaterThan(other);
    }
}
