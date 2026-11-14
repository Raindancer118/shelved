package de.raindancer118.shelved;

import de.raindancer118.shelved.ai.LibraryAi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Scripted stand-in for Claude in integration tests. */
public class FakeLibraryAi implements LibraryAi {

    public volatile ShelfReading nextShelf = new ShelfReading(List.of(), List.of());
    public volatile List<SeriesGuess> seriesAnswer = List.of();
    public volatile SeriesCatalog catalogAnswer = new SeriesCatalog(List.of(), true, "");
    public final List<Integer> tileCounts = new CopyOnWriteArrayList<>();
    public final List<List<String>> seriesQuestions = new CopyOnWriteArrayList<>();
    public final List<String> catalogQuestions = new CopyOnWriteArrayList<>();

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
    public List<SeriesGuess> resolveSeries(List<BookRef> books) {
        seriesQuestions.add(books.stream().map(BookRef::title).toList());
        List<SeriesGuess> out = new ArrayList<>();
        for (BookRef ref : books) {
            seriesAnswer.stream().filter(g -> ref.title().equals(g.id()))
                    .findFirst()
                    .ifPresent(g -> out.add(new SeriesGuess(ref.id(), g.series(), g.volume())));
        }
        return out;
    }

    @Override
    public SeriesCatalog seriesCatalog(String seriesName, String author, String language) {
        catalogQuestions.add(seriesName);
        return catalogAnswer;
    }
}
