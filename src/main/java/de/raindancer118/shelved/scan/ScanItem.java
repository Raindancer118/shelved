package de.raindancer118.shelved.scan;

import de.raindancer118.shelved.ai.LibraryAi;
import de.raindancer118.shelved.metadata.BookMetadata;

import java.util.List;
import java.util.UUID;

public record ScanItem(int index, LibraryAi.SpineReading reading, Status status, BookMetadata candidate,
                       List<BookMetadata> alternatives, UUID ownedBookId) {

    public enum Status {
        /** Found in a catalogue, not in the library yet. */
        NEW,
        /** Already in the library. */
        OWNED,
        /** Spine hard to read or no convincing catalogue match: the user should look. */
        UNSURE,
        /** The same book as an earlier one on this photo: a second copy only if the user says so. */
        DUPLICATE
    }

    public record Result(List<ScanItem> items, List<LibraryAi.BoardReading> boards) {
        public Result {
            boards = boards == null ? List.of() : boards;
        }
    }
}
