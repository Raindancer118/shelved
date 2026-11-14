package de.raindancer118.shelved.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** Everything shelved asks an AI model. One implementation talks to Claude; tests use their own. */
public interface LibraryAi {

    boolean available();

    ShelfReading readShelf(List<byte[]> jpegTiles);

    ShelfReading readSingleBook(byte[] jpeg);

    List<SeriesGuess> resolveSeries(List<BookRef> books);

    SeriesCatalog seriesCatalog(String seriesName, String author, String language);

    record SpineReading(
            @JsonPropertyDescription("Title as printed on the spine or cover, completed only if you are sure which book it is")
            String title,
            @JsonPropertyDescription("Author name(s) as printed, e.g. 'Terry Pratchett'. Empty list if not visible and not certain")
            List<String> authors,
            @JsonPropertyDescription("Name of the literary series as readers know it in this edition's language (e.g. 'Scheibenwelt'), not a publisher imprint. Empty string if standalone or unknown")
            String series,
            @JsonPropertyDescription("Volume number within that series, 0 if none or unknown")
            int volume,
            @JsonPropertyDescription("Publisher name if visible, else empty string")
            String publisher,
            @JsonPropertyDescription("high = clearly readable, medium = partly readable but fairly sure, low = guess")
            String confidence,
            @JsonPropertyDescription("The raw text you could actually read on this spine")
            String spineText) {
    }

    record ShelfReading(
            @JsonPropertyDescription("Every visible book exactly once, from left to right")
            List<SpineReading> books) {
    }

    record BookRef(String id, String title, String author, String publisher, Integer year) {
    }

    record SeriesGuess(
            @JsonPropertyDescription("The id given in the request")
            String id,
            @JsonPropertyDescription("Series name as readers know it in the edition's language, empty string for standalone books")
            String series,
            @JsonPropertyDescription("Volume number in the series' usual reading order, 0 if standalone or unknown")
            int volume) {
    }

    record SeriesGuesses(List<SeriesGuess> books) {
    }

    record CatalogVolume(
            @JsonPropertyDescription("Volume number in the series' usual order")
            int number,
            @JsonPropertyDescription("Title of that volume in the requested language")
            String title) {
    }

    record SeriesCatalog(
            List<CatalogVolume> volumes,
            @JsonPropertyDescription("true if the series is finished or the list is complete as of today")
            boolean complete,
            @JsonPropertyDescription("One short sentence about the source or caveats, in German")
            String note) {
    }

    class AiException extends RuntimeException {
        public AiException(String message) {
            super(message);
        }

        public AiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
