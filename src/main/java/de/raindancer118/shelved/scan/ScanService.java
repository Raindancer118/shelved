package de.raindancer118.shelved.scan;

import de.raindancer118.shelved.ai.LibraryAi;
import de.raindancer118.shelved.book.Book;
import de.raindancer118.shelved.book.BookService;
import de.raindancer118.shelved.book.NotFoundException;
import de.raindancer118.shelved.metadata.BookMetadata;
import de.raindancer118.shelved.metadata.MetadataService;
import de.raindancer118.shelved.metadata.Similarity;
import de.raindancer118.shelved.shelf.ShelfRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);
    private static final double MIN_TITLE_MATCH = 0.5;

    public record Decision(int index, boolean take, String isbn13, Boolean anotherCopy) {
    }

    public record AcceptResult(List<UUID> created, List<UUID> placed) {
    }

    private final ScanRepository scans;
    private final ShelfRepository shelves;
    private final LibraryAi ai;
    private final MetadataService metadata;
    private final BookService books;
    private final RateLimiter limiter;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final Executor executor;

    public ScanService(ScanRepository scans, ShelfRepository shelves, LibraryAi ai, MetadataService metadata,
                       BookService books, RateLimiter limiter, JsonMapper json, TransactionTemplate tx,
                       @Qualifier("aiExecutor") Executor executor) {
        this.scans = scans;
        this.shelves = shelves;
        this.ai = ai;
        this.metadata = metadata;
        this.books = books;
        this.limiter = limiter;
        this.json = json;
        this.tx = tx;
        this.executor = executor;
    }

    public Scan submit(String owner, Scan.Mode mode, byte[] image, UUID shelfId, Integer board) {
        if (!ai.available()) {
            throw new LibraryAi.AiException("Bilderkennung ist nicht eingerichtet (kein API-Key)");
        }
        if (shelfId != null) {
            var shelf = shelves.findByIdAndOwnerId(shelfId, owner).orElseThrow(() -> new NotFoundException("Regal"));
            if (board == null || board < 1 || board > shelf.getBoardWidthsMm().size()) {
                throw new IllegalArgumentException("Diesen Boden gibt es in dem Regal nicht");
            }
        }
        BufferedImage img = decode(image);
        limiter.acquire(owner);
        Scan scan = tx.execute(s -> scans.save(new Scan(owner, mode, shelfId, shelfId == null ? null : board)));
        executor.execute(() -> process(scan.getId(), owner, mode, img));
        return scan;
    }

    public Scan get(String owner, UUID id) {
        return scans.findByIdAndOwnerId(id, owner).orElseThrow(() -> new NotFoundException("Scan"));
    }

    public ScanItem.Result result(Scan scan) {
        return scan.getResultJson() == null ? new ScanItem.Result(List.of()) : json.readValue(scan.getResultJson(), ScanItem.Result.class);
    }

    void process(UUID scanId, String owner, Scan.Mode mode, BufferedImage img) {
        try {
            LibraryAi.ShelfReading reading = mode == Scan.Mode.SHELF
                    ? readShelf(img)
                    : ai.readSingleBook(ImageTiler.jpeg(ImageTiler.fit(img)));
            List<ScanItem> items = new ArrayList<>();
            List<LibraryAi.SpineReading> spines = reading.books() == null ? List.of() : reading.books();
            for (int i = 0; i < spines.size(); i++) {
                ScanItem item = match(owner, i, spines.get(i));
                items.add(items.stream().anyMatch(earlier -> sameBook(earlier, item)) && item.ownedBookId() == null
                        ? new ScanItem(item.index(), item.reading(), ScanItem.Status.DUPLICATE, item.candidate(), item.alternatives(), null)
                        : item);
            }
            String result = json.writeValueAsString(new ScanItem.Result(items));
            tx.executeWithoutResult(s -> scans.findById(scanId).ifPresent(sc -> sc.done(result)));
        } catch (RuntimeException e) {
            log.warn("Scan {} failed", scanId, e);
            tx.executeWithoutResult(s -> scans.findById(scanId).ifPresent(sc -> sc.failed(e.getMessage())));
        }
    }

    private LibraryAi.ShelfReading readShelf(BufferedImage img) {
        ImageTiler.Split split = ImageTiler.split(img);
        return ai.readShelf(split.images().stream().map(ImageTiler::jpeg).toList(),
                split.direction() == ImageTiler.Direction.TOP_TO_BOTTOM);
    }

    ScanItem match(String owner, int index, LibraryAi.SpineReading r) {
        String author = r.authors() == null || r.authors().isEmpty() ? null : r.authors().getFirst();
        if (r.title() == null || r.title().isBlank()) {
            return new ScanItem(index, r, ScanItem.Status.UNSURE, null, List.of(), null);
        }
        List<BookMetadata> found = candidates(r.title(), author, r.publisher());
        if (found.isEmpty() && !Similarity.mainTitle(r.title()).equals(r.title())) {
            // "Zessalonn – Die Legenden der Samerier" is catalogued as "Zessalonn", sometimes with another author spelling
            found = candidates(Similarity.mainTitle(r.title()), null, r.publisher());
        }
        BookMetadata best = found.isEmpty() ? null : found.getFirst();
        List<Book> owned = books.findOwned(owner, best == null ? null : best.isbn13(), r.title(), author);
        ScanItem.Status status;
        if (!owned.isEmpty()) {
            status = ScanItem.Status.OWNED;
        } else if (best == null || "low".equalsIgnoreCase(r.confidence())) {
            status = ScanItem.Status.UNSURE;
        } else {
            status = ScanItem.Status.NEW;
        }
        return new ScanItem(index, r, status, best, found.size() > 1 ? found.subList(1, found.size()) : List.of(),
                owned.isEmpty() ? null : owned.getFirst().getId());
    }

    static boolean sameBook(ScanItem a, ScanItem b) {
        if (a.candidate() != null && b.candidate() != null && a.candidate().isbn13() != null) {
            return a.candidate().isbn13().equals(b.candidate().isbn13());
        }
        String authorA = a.reading().authors() == null || a.reading().authors().isEmpty() ? "" : a.reading().authors().getFirst();
        String authorB = b.reading().authors() == null || b.reading().authors().isEmpty() ? "" : b.reading().authors().getFirst();
        return Similarity.title(a.reading().title(), b.reading().title()) >= 0.9
                && (authorA.isBlank() || authorB.isBlank() || Similarity.surname(authorA, authorB));
    }

    private List<BookMetadata> candidates(String title, String author, String publisher) {
        return metadata.search(title, author, publisher).stream()
                .filter(m -> Similarity.title(title, m.title(), m.subtitle()) >= MIN_TITLE_MATCH)
                .limit(4)
                .toList();
    }

    /**
     * Adds the chosen books. For a shelf scan, every book on the photo (new or already owned)
     * gets that board and its position on the photo as its current place.
     */
    public AcceptResult accept(String owner, UUID scanId, List<Decision> decisions) {
        Scan scan = get(owner, scanId);
        if (scan.getStatus() != Scan.Status.DONE) {
            throw new IllegalStateException("Scan ist nicht (mehr) offen");
        }
        List<ScanItem> items = result(scan).items();
        List<UUID> created = new ArrayList<>();
        List<UUID> placed = new ArrayList<>();
        int boards = scan.getShelfId() == null ? 0
                : shelves.findById(scan.getShelfId()).map(sh -> sh.getBoardWidthsMm().size()).orElse(0);
        java.util.Map<Integer, Integer> positionInRow = new java.util.HashMap<>();
        for (ScanItem item : items) {
            Decision d = decisions.stream().filter(x -> x.index() == item.index()).findFirst().orElse(null);
            UUID bookId = item.ownedBookId();
            if (d != null && d.take() && bookId == null) {
                BookService.Added added = add(owner, item, d.isbn13(), Boolean.TRUE.equals(d.anotherCopy()));
                bookId = added.book().getId();
                if (added.created()) {
                    created.add(bookId);
                }
            }
            if (bookId != null && scan.getShelfId() != null) {
                int row = Math.max(1, item.reading().row());
                int board = scan.getBoardNumber() + row - 1;
                if (board <= boards) {
                    int position = positionInRow.merge(row, 1, Integer::sum);
                    books.move(owner, bookId, scan.getShelfId(), board, position);
                    placed.add(bookId);
                }
            }
        }
        tx.executeWithoutResult(s -> scans.findById(scanId).ifPresent(Scan::accepted));
        return new AcceptResult(created, placed);
    }

    /** New book, or (same book already there, e.g. twice on the photo) one more copy if asked, else nothing new. */
    private BookService.Added add(String owner, ScanItem item, String chosenIsbn, boolean anotherCopy) {
        try {
            return new BookService.Added(create(owner, item, chosenIsbn), true);
        } catch (de.raindancer118.shelved.book.DuplicateBookException e) {
            Book existing = anotherCopy ? books.setCopies(owner, e.existing().getId(), e.existing().getCopies() + 1) : e.existing();
            return new BookService.Added(existing, false);
        }
    }

    private Book create(String owner, ScanItem item, String chosenIsbn) {
        LibraryAi.SpineReading r = item.reading();
        var hint = new BookService.SeriesHint(r.series(), r.volume() > 0 ? r.volume() : null);
        BookMetadata chosen = null;
        if (chosenIsbn != null) {
            chosen = java.util.stream.Stream.concat(java.util.stream.Stream.ofNullable(item.candidate()), item.alternatives().stream())
                    .filter(m -> chosenIsbn.equals(m.isbn13()))
                    .findFirst()
                    .or(() -> metadata.byIsbn(chosenIsbn))
                    .orElse(null);
        } else if (item.candidate() != null) {
            chosen = item.candidate();
        }
        if (chosen != null) {
            return books.createFromMetadata(owner, chosen, hint, false);
        }
        var in = new de.raindancer118.shelved.book.BookInput(r.title(), null, r.authors(), null,
                blank(r.publisher()), null, null, null, null, null, null, null, null);
        BookService.Added a = books.addManual(owner, in, hint, false);
        return a.book();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public static BufferedImage decode(byte[] image) {
        return Images.decode(image);
    }
}
