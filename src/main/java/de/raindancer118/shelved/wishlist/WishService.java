package de.raindancer118.shelved.wishlist;

import de.raindancer118.shelved.book.Book;
import de.raindancer118.shelved.book.BookService;
import de.raindancer118.shelved.book.CoverStore;
import de.raindancer118.shelved.book.DuplicateBookException;
import de.raindancer118.shelved.book.Isbn;
import de.raindancer118.shelved.book.NotFoundException;
import de.raindancer118.shelved.metadata.BookMetadata;
import de.raindancer118.shelved.metadata.MetadataService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class WishService {

    /** {@code created=false}: the wish was already on the list. */
    public record Added(Wish wish, boolean created) {
    }

    /** A wish and, if the library has it by now, that book. */
    public record Entry(Wish wish, Book owned) {
    }

    private final WishRepository wishes;
    private final BookService books;
    private final MetadataService metadata;
    private final CoverStore covers;

    public WishService(WishRepository wishes, BookService books, MetadataService metadata, CoverStore covers) {
        this.wishes = wishes;
        this.books = books;
        this.metadata = metadata;
        this.covers = covers;
    }

    @Transactional(readOnly = true)
    public List<Entry> all(String owner) {
        List<Book> library = books.all(owner);
        return wishes.findByOwnerIdOrderByCreatedAtDesc(owner).stream()
                .map(w -> new Entry(w, owned(library, w).orElse(null)))
                .toList();
    }

    /** Wishes the library has meanwhile and nobody decided to keep: the app asks whether they may go. */
    @Transactional(readOnly = true)
    public List<Entry> fulfilled(String owner) {
        return all(owner).stream().filter(e -> e.owned() != null && !e.wish().isKeepWhenOwned()).toList();
    }

    /** By ISBN the catalogue fills in the rest; without one, title (and author) as typed, e.g. a missing series volume. */
    @Transactional
    public Added add(String owner, String isbn, String title, List<String> authors, String seriesName, Integer seriesVolume) {
        Wish w;
        String coverUrl = null;
        if (isbn != null && !isbn.isBlank()) {
            String isbn13 = Isbn.toIsbn13(isbn).orElseThrow(() -> new IllegalArgumentException("Keine gültige ISBN"));
            BookMetadata m = metadata.byIsbn(isbn13).orElseThrow(() -> new NotFoundException("Buch zu dieser ISBN"));
            w = new Wish(owner, m.title());
            w.setIsbn13(isbn13);
            w.setSubtitle(m.subtitle());
            w.setAuthors(m.authors());
            w.setPublisher(m.publisher());
            w.setPublishedYear(m.publishedYear());
            w.setListPriceCents(m.listPriceCents());
            coverUrl = m.coverUrl();
            w.setSeries(m.seriesName() != null ? m.seriesName() : seriesName, m.seriesName() != null ? m.seriesVolume() : seriesVolume);
        } else {
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("Titel oder ISBN fehlt");
            }
            w = new Wish(owner, title.trim());
            w.setAuthors(authors);
            w.setSeries(seriesName, seriesVolume);
        }

        List<Book> library = books.all(owner);
        Optional<Book> owned = owned(library, w);
        if (owned.isPresent()) {
            throw new DuplicateBookException(owned.get());
        }
        Optional<Wish> existing = wishes.findByOwnerIdOrderByCreatedAtDesc(owner).stream().filter(x -> same(x, w)).findFirst();
        if (existing.isPresent()) {
            return new Added(existing.get(), false);
        }
        if (w.getIsbn13() != null) {
            covers.fetch(w.getId(), CoverStore.candidates(w.getIsbn13(), coverUrl)).ifPresent(w::setCoverFile);
        }
        return new Added(wishes.save(w), true);
    }

    @Transactional(readOnly = true)
    public Wish get(String owner, UUID id) {
        return wishes.findByIdAndOwnerId(id, owner).orElseThrow(() -> new NotFoundException("Wunsch"));
    }

    @Transactional
    public void delete(String owner, UUID id) {
        Wish w = get(owner, id);
        wishes.delete(w);
        covers.delete(w.getCoverFile());
    }

    /** The answer to "remove these from the list?": some go, the rest stay and won't be asked about again. */
    @Transactional
    public void resolve(String owner, Collection<UUID> remove, Collection<UUID> keep) {
        Optional.ofNullable(remove).orElse(List.of()).forEach(id -> delete(owner, id));
        Optional.ofNullable(keep).orElse(List.of()).forEach(id -> get(owner, id).keepWhenOwned());
    }

    private static Optional<Book> owned(List<Book> library, Wish w) {
        if (w.getIsbn13() != null) {
            Optional<Book> byIsbn = library.stream().filter(b -> w.getIsbn13().equals(b.getIsbn13())).findFirst();
            if (byIsbn.isPresent()) {
                return byIsbn;
            }
        }
        return library.stream().filter(b -> BookService.sameWork(b.getTitle(), b.getAuthors(), w.getTitle(), firstAuthor(w))).findFirst();
    }

    private static boolean same(Wish a, Wish b) {
        if (a.getIsbn13() != null && Objects.equals(a.getIsbn13(), b.getIsbn13())) {
            return true;
        }
        return BookService.sameWork(a.getTitle(), a.getAuthors(), b.getTitle(), firstAuthor(b));
    }

    private static String firstAuthor(Wish w) {
        return w.getAuthors().isEmpty() ? null : w.getAuthors().getFirst();
    }
}
