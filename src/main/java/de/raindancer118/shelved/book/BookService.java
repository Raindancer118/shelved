package de.raindancer118.shelved.book;

import de.raindancer118.shelved.metadata.BookMetadata;
import de.raindancer118.shelved.metadata.MetadataService;
import de.raindancer118.shelved.metadata.Similarity;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BookService {

    /** Published after books were added, so the series resolver can look at them. */
    public record BooksAdded(String ownerId) {
    }

    /** {@code created=false}: the book was already there and got one more copy. */
    public record Added(Book book, boolean created) {
    }

    public record SeriesHint(String name, Integer volume) {
        public static SeriesHint none() {
            return new SeriesHint(null, null);
        }

        boolean present() {
            return name != null && !name.isBlank();
        }
    }

    private final BookRepository books;
    private final CoverStore covers;
    private final MetadataService metadata;
    private final ApplicationEventPublisher events;
    private final java.util.concurrent.Executor executor;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public BookService(BookRepository books, CoverStore covers, MetadataService metadata, ApplicationEventPublisher events,
                       @org.springframework.beans.factory.annotation.Qualifier("aiExecutor") java.util.concurrent.Executor executor,
                       org.springframework.transaction.support.TransactionTemplate tx) {
        this.executor = executor;
        this.tx = tx;
        this.books = books;
        this.covers = covers;
        this.metadata = metadata;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<Book> all(String owner) {
        return books.findByOwnerId(owner);
    }

    @Transactional(readOnly = true)
    public Book get(String owner, UUID id) {
        return books.findByIdAndOwnerId(id, owner).orElseThrow(() -> new NotFoundException("Buch"));
    }

    /** Single book: cover and blurb are fetched right away, so the detail page is complete when it opens. */
    public Added addFromMetadata(String owner, BookMetadata m, SeriesHint hint, boolean anotherCopy) {
        Optional<Book> existing = duplicate(owner, m.isbn13(), m.title(), m.authors());
        if (existing.isPresent()) {
            return new Added(oneMoreCopyOrRefuse(existing.get(), anotherCopy), false);
        }
        return new Added(createFromMetadata(owner, m, hint, true), true);
    }

    /**
     * Stores the book from catalogue data. With {@code enrichNow=false} (a whole shelf at once) cover and blurb
     * are downloaded in the background, otherwise accepting 40 books would take minutes and hit proxy timeouts.
     */
    public Book createFromMetadata(String owner, BookMetadata m, SeriesHint hint, boolean enrichNow) {
        duplicate(owner, m.isbn13(), m.title(), m.authors()).ifPresent(d -> {
            throw new DuplicateBookException(d);
        });
        Book b = new Book(owner, m.title());
        b.setIsbn13(m.isbn13());
        b.setSubtitle(m.subtitle());
        b.setAuthors(m.authors());
        b.setPublisher(m.publisher());
        b.setPublishedYear(m.publishedYear());
        b.setPageCount(m.pageCount());
        b.setHeightMm(m.heightMm());
        b.setFormat(m.format());
        b.setLanguage(m.language());
        b.setListPriceCents(m.listPriceCents());
        b.setMetadataSource(m.source());
        if (m.seriesName() != null) {
            b.setSeries(m.seriesName(), m.seriesVolume(), SeriesSource.CATALOG);
            b.setSeriesChecked(true);
        } else {
            applySeries(b, hint);
        }
        if (enrichNow) {
            enrich(b, m);
        }
        Book saved = books.save(b);
        if (!enrichNow) {
            UUID id = saved.getId();
            executor.execute(() -> {
                String cover = covers.fetch(id, CoverStore.candidates(m.isbn13(), m.coverUrl())).orElse(null);
                String blurb = metadata.description(m).orElse(null);
                tx.executeWithoutResult(s -> books.findById(id).ifPresent(x -> {
                    if (x.getCoverFile() == null) {
                        x.setCoverFile(cover);
                    }
                    if (x.getDescription() == null) {
                        x.setDescription(blurb);
                    }
                }));
            });
        }
        events.publishEvent(new BooksAdded(owner));
        return saved;
    }

    private void enrich(Book target, BookMetadata m) {
        covers.fetch(target.getId(), CoverStore.candidates(m.isbn13(), m.coverUrl())).ifPresent(target::setCoverFile);
        metadata.description(m).ifPresent(target::setDescription);
    }

    public Added addManual(String owner, BookInput in, SeriesHint hint, boolean anotherCopy) {
        Optional<Book> existing = duplicate(owner, Optional.ofNullable(in.isbn()).flatMap(Isbn::toIsbn13).orElse(null), in.title(), in.authors());
        if (existing.isPresent()) {
            return new Added(oneMoreCopyOrRefuse(existing.get(), anotherCopy), false);
        }
        return new Added(addManual(owner, in, hint), true);
    }

    private Book addManual(String owner, BookInput in, SeriesHint hint) {
        Book b = new Book(owner, in.title());
        apply(b, in);
        if (in.seriesName() == null || in.seriesName().isBlank()) {
            applySeries(b, hint);
        }
        if (b.getIsbn13() != null) {
            covers.fetch(b.getId(), CoverStore.candidates(b.getIsbn13(), null)).ifPresent(b::setCoverFile);
        }
        Book saved = books.save(b);
        events.publishEvent(new BooksAdded(owner));
        return saved;
    }

    private Book oneMoreCopyOrRefuse(Book existing, boolean anotherCopy) {
        if (!anotherCopy) {
            throw new DuplicateBookException(existing);
        }
        return setCopies(existing.getOwnerId(), existing.getId(), existing.getCopies() + 1);
    }

    @Transactional
    public Book setCopies(String owner, UUID id, int copies) {
        Book b = get(owner, id);
        b.setCopies(copies);
        return books.save(b); // also called from inside this class, where @Transactional doesn't apply
    }

    /** Same ISBN, or same title with the same author surname. */
    @Transactional(readOnly = true)
    public Optional<Book> duplicate(String owner, String isbn13, String title, List<String> authors) {
        String author = authors == null || authors.isEmpty() ? null : authors.getFirst();
        return findOwned(owner, isbn13, title, author).stream().findFirst();
    }

    @Transactional
    public Book update(String owner, UUID id, BookInput in) {
        Book b = get(owner, id);
        apply(b, in);
        return b;
    }

    @Transactional
    public void delete(String owner, UUID id) {
        Book b = get(owner, id);
        books.delete(b);
        covers.delete(b.getCoverFile());
    }

    @Transactional
    public Book replaceCover(String owner, UUID id, byte[] image, String ext) {
        Book b = get(owner, id);
        String old = b.getCoverFile();
        b.setCoverFile(covers.save(b.getId(), image, ext));
        covers.delete(old);
        return b;
    }

    @Transactional
    public Book move(String owner, UUID id, UUID shelfId, Integer board, Integer position) {
        Book b = get(owner, id);
        b.placeAt(shelfId, board, position);
        return b;
    }

    /** Books in the library that are this one: same ISBN, or same title and author surname. */
    @Transactional(readOnly = true)
    public List<Book> findOwned(String owner, String isbn13, String title, String author) {
        if (isbn13 != null) {
            List<Book> byIsbn = books.findByOwnerIdAndIsbn13(owner, isbn13);
            if (!byIsbn.isEmpty()) {
                return byIsbn;
            }
        }
        if (title == null || title.isBlank()) {
            return List.of();
        }
        return books.findByOwnerId(owner).stream().filter(b -> sameWork(b.getTitle(), b.getAuthors(), title, author)).toList();
    }

    /** Similar title and the same author surname; a side without authors doesn't rule it out. */
    public static boolean sameWork(String title, List<String> authors, String otherTitle, String otherAuthor) {
        return title != null && otherTitle != null && Similarity.title(otherTitle, title) >= 0.8
                && (otherAuthor == null || otherAuthor.isBlank() || authors == null || authors.isEmpty()
                    || authors.stream().anyMatch(a -> Similarity.surname(a, otherAuthor)));
    }

    private static void applySeries(Book b, SeriesHint hint) {
        if (hint != null && hint.present()) {
            b.setSeries(hint.name(), hint.volume(), SeriesSource.PHOTO);
        }
    }

    private static void apply(Book b, BookInput in) {
        b.setTitle(in.title().trim());
        b.setSubtitle(blankToNull(in.subtitle()));
        b.setAuthors(in.authors() == null ? List.of() : in.authors().stream().filter(a -> a != null && !a.isBlank()).map(String::trim).toList());
        b.setIsbn13(Optional.ofNullable(in.isbn()).flatMap(Isbn::toIsbn13).orElse(null));
        b.setPublisher(blankToNull(in.publisher()));
        b.setPublishedYear(in.publishedYear());
        b.setPageCount(in.pageCount());
        b.setThicknessMm(in.thicknessMm());
        b.setFormat(in.format());
        b.setListPriceCents(in.listPriceCents());
        b.setDescription(blankToNull(in.description()));
        if (in.seriesName() != null) {
            // only an actual change is the user's decision; saving the form unchanged keeps the source
            String name = in.seriesName().isBlank() ? null : de.raindancer118.shelved.series.SeriesNames.normalize(in.seriesName());
            Integer volume = name == null || in.seriesVolume() == null || in.seriesVolume() <= 0 ? null : in.seriesVolume();
            if (!java.util.Objects.equals(name, b.getSeriesName()) || !java.util.Objects.equals(volume, b.getSeriesVolume())) {
                b.setSeriesManually(name, volume);
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
