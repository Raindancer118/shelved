package de.raindancer118.shelved;

import de.raindancer118.shelved.ai.LibraryAi;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Scripted stand-in for Claude in integration tests. */
public class FakeLibraryAi implements LibraryAi {

    public volatile ShelfReading nextShelf = new ShelfReading(List.of(), List.of());
    public volatile FaceLocation nextFace = FaceLocation.none();
    public final List<Integer> tileCounts = new CopyOnWriteArrayList<>();

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public ShelfReading readShelf(List<byte[]> jpegTiles, boolean topToBottom) {
        tileCounts.add(jpegTiles.size());
        return nextShelf;
    }

    @Override
    public ShelfReading readSingleBook(byte[] jpeg) {
        return nextShelf;
    }

    @Override
    public FaceLocation locateFace(byte[] jpeg, String face) {
        return nextFace;
    }
}
