package de.raindancer118.shelved.author;

import de.raindancer118.shelved.book.Names;
import de.raindancer118.shelved.book.Titles;
import de.raindancer118.shelved.metadata.BookMetadata;
import de.raindancer118.shelved.metadata.DnbClient;
import de.raindancer118.shelved.metadata.OpenLibraryClient;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Authors from Open Library (portrait, dates, bio), their books in German from the national library. */
@Service
public class AuthorService {

    public record Author(String key, String name, String birthDate, String topWork, String photo) {
    }

    public record Profile(String name, String birthDate, String deathDate, String bio, String photo, List<BookMetadata> books) {
    }

    private final OpenLibraryClient openLibrary;
    private final DnbClient dnb;

    public AuthorService(OpenLibraryClient openLibrary, DnbClient dnb) {
        this.openLibrary = openLibrary;
        this.dnb = dnb;
    }

    /** Authors whose name holds every word of the query ("maas" → Sarah J. Maas), best known first. */
    public List<Author> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> words = words(query);
        return openLibrary.searchAuthors(query.trim()).stream()
                .filter(a -> words(a.name()).containsAll(words) || words.stream().allMatch(w -> Names.collationKey(a.name()).contains(w)))
                .sorted(Comparator.comparingInt(OpenLibraryClient.AuthorHit::works).reversed())
                .limit(5)
                .map(a -> new Author(a.key(), a.name(), a.birthDate(), a.topWork(), photo(a.key())))
                .toList();
    }

    public Profile profile(String name) {
        Optional<OpenLibraryClient.AuthorDetail> detail = openLibrary.searchAuthors(name).stream()
                .filter(a -> Names.collationKey(a.name()).equals(Names.collationKey(name)))
                .max(Comparator.comparingInt(OpenLibraryClient.AuthorHit::works))
                .flatMap(a -> openLibrary.author(a.key()));
        return new Profile(detail.map(OpenLibraryClient.AuthorDetail::name).orElse(name.trim()),
                detail.map(OpenLibraryClient.AuthorDetail::birthDate).orElse(null),
                detail.map(OpenLibraryClient.AuthorDetail::deathDate).orElse(null),
                detail.map(OpenLibraryClient.AuthorDetail::bio).orElse(null),
                detail.filter(OpenLibraryClient.AuthorDetail::hasPhoto).map(d -> photo(d.key())).orElse(null),
                germanBooks(name));
    }

    /** One entry per title: the newest edition, and among equally new ones the one with a price. */
    private List<BookMetadata> germanBooks(String name) {
        Map<String, BookMetadata> byTitle = new LinkedHashMap<>();
        for (BookMetadata m : dnb.byAuthor(name)) {
            byTitle.merge(Names.collationKey(Titles.sortForm(m.title())), m, (a, b) -> newer(b, a) ? b : a);
        }
        return byTitle.values().stream()
                .sorted(Comparator.comparing(BookMetadata::publishedYear, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private static boolean newer(BookMetadata a, BookMetadata b) {
        int ya = a.publishedYear() == null ? 0 : a.publishedYear(), yb = b.publishedYear() == null ? 0 : b.publishedYear();
        return ya != yb ? ya > yb : a.listPriceCents() != null && b.listPriceCents() == null;
    }

    private static String photo(String key) {
        return "/api/covers/author/" + key;
    }

    private static List<String> words(String s) {
        return Arrays.stream(Names.collationKey(s).split("[^\\p{L}\\p{N}]+")).filter(w -> !w.isBlank()).toList();
    }
}
