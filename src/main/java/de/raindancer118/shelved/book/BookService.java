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
    public Book addFromMetadata(String owner, BookMetadata m, SeriesHint hint) {
        return addFromMetadata(owner, m, hint, true);
    }

    /**
     * Stores the book from catalogue data. With {@code enrichNow=false} (a whole shelf at once) cover and blurb
     * are downloaded in the background, otherwise accepting 40 books would take minutes and hit proxy timeouts.
     */
    public Book addFromMetadata(String owner, BookMetadata m, SeriesHint hint, boolean enrichNow) {
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
        applySeries(b, hint);
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

    public Book addManual(String owner, BookInput in, SeriesHint hint) {
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
        return books.findByOwnerId(owner).stream()
                .filter(b -> Similarity.title(title, b.getTitle()) >= 0.8)
                .filter(b -> author == null || author.isBlank() || b.getAuthors().isEmpty()
                        || b.getAuthors().stream().anyMatch(a -> Similarity.surname(a, author)))
                .toList();
    }

    private static void applySeries(Book b, SeriesHint hint) {
        if (hint != null && hint.present()) {
            b.setSeriesName(hint.name());
            b.setSeriesVolume(hint.volume());
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
            b.setSeriesName(in.seriesName());
            b.setSeriesVolume(in.seriesVolume());
            b.setSeriesChecked(true);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
