package de.raindancer118.shelved.metadata;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Star averages from sources that allow showing them (with their name); kept a day so pages don't spend quota. */
@Service
public class RatingService {

    private record Cached(List<Rating> ratings, Instant at) {
    }

    private final GoogleBooksClient google;
    private final OpenLibraryClient openLibrary;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public RatingService(GoogleBooksClient google, OpenLibraryClient openLibrary) {
        this.google = google;
        this.openLibrary = openLibrary;
    }

    public List<Rating> ratings(String isbn13, String title, String author) {
        Cached c = cache.get(isbn13);
        if (c != null && c.at().isAfter(Instant.now().minus(Duration.ofDays(1)))) {
            return c.ratings();
        }
        // Open Library rates works: the edition's own entry first, else the work by title and author
        List<Rating> found = Stream.of(google.rating(isbn13), openLibrary.rating(isbn13).or(() -> openLibrary.workRating(title, author)))
                .flatMap(Optional::stream).toList();
        if (cache.size() > 5_000) {
            cache.clear();
        }
        cache.put(isbn13, new Cached(found, Instant.now()));
        return found;
    }
}
