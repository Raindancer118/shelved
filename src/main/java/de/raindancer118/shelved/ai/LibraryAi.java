package de.raindancer118.shelved.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** Everything shelved asks an AI model. One implementation talks to Claude; tests use their own. */
public interface LibraryAi {

    boolean available();

    /** @param topToBottom true if the tiles are the top and bottom half of one photo, false if left-to-right segments */
    ShelfReading readShelf(List<byte[]> jpegTiles, boolean topToBottom);

    ShelfReading readSingleBook(byte[] jpeg);

    /** Where one face of a book ("back cover", "spine", "fore edge") lies in a photo, to cut it out straight. */
    FaceLocation locateFace(byte[] jpeg, String face);

    record Corner(
            @JsonPropertyDescription("Horizontal position as a fraction of the photo width, 0.0 = left edge, 1.0 = right edge")
            double x,
            @JsonPropertyDescription("Vertical position as a fraction of the photo height, 0.0 = top edge, 1.0 = bottom edge")
            double y) {
    }

    record FaceLocation(
            @JsonPropertyDescription("true only if the asked face of the book is clearly visible with all four corners")
            boolean found,
            @JsonPropertyDescription("Corner where the head (top edge) of the book meets the face's left side as you look at the face")
            Corner headLeft,
            @JsonPropertyDescription("Corner where the head (top edge) of the book meets the face's right side")
            Corner headRight,
            @JsonPropertyDescription("Corner where the tail (bottom edge) of the book meets the face's right side")
            Corner tailRight,
            @JsonPropertyDescription("Corner where the tail (bottom edge) of the book meets the face's left side")
            Corner tailLeft) {

        public static FaceLocation none() {
            return new FaceLocation(false, null, null, null, null);
        }
    }

    record SpineReading(
            @JsonPropertyDescription("Title as printed on the spine or cover, completed only if you are sure which book it is")
            String title,
            @JsonPropertyDescription("Author name(s) as printed, e.g. 'Terry Pratchett'. Empty list if not visible and not certain")
            List<String> authors,
            @JsonPropertyDescription("Publisher name if visible, else empty string")
            String publisher,
            @JsonPropertyDescription("high = clearly readable, medium = partly readable but fairly sure, low = guess")
            String confidence,
            @JsonPropertyDescription("Only when confidence is not high: the raw text you could actually read. Otherwise empty string")
            String spineText,
            @JsonPropertyDescription("Shelf board in the photo: 1 = topmost board visible, counting downwards")
            int row,
            @JsonPropertyDescription("Width of this spine as a fraction (0.0–1.0) of its board's usable width between the side walls, measured on the photo; 0 if you cannot tell")
            double share) {
    }

    record BoardReading(
            @JsonPropertyDescription("Board number as used for the books' row: 1 = topmost visible board")
            int row,
            @JsonPropertyDescription("Fraction 0.0–1.0 of this board's usable width between its side walls that the books take up")
            double fill,
            @JsonPropertyDescription("true only if both side walls (left and right end) of this board are visible in the photo")
            boolean bothEndsVisible) {
    }

    record ShelfReading(
            @JsonPropertyDescription("Every visible book exactly once: top board first, each board left to right")
            List<SpineReading> books,
            @JsonPropertyDescription("One entry per board row that has books on it")
            List<BoardReading> boards) {
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
