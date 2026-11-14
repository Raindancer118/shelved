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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);
    private static final double MIN_TITLE_MATCH = 0.5;

    public record Decision(int index, boolean take, String isbn13) {
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
                    ? ai.readShelf(ImageTiler.tiles(img).stream().map(ImageTiler::jpeg).toList())
                    : ai.readSingleBook(ImageTiler.jpeg(ImageTiler.fit(img)));
            List<ScanItem> items = new ArrayList<>();
            List<LibraryAi.SpineReading> spines = reading.books() == null ? List.of() : reading.books();
            for (int i = 0; i < spines.size(); i++) {
                items.add(match(owner, i, spines.get(i)));
            }
            String result = json.writeValueAsString(new ScanItem.Result(items));
            tx.executeWithoutResult(s -> scans.findById(scanId).ifPresent(sc -> sc.done(result)));
        } catch (RuntimeException e) {
            log.warn("Scan {} failed", scanId, e);
            tx.executeWithoutResult(s -> scans.findById(scanId).ifPresent(sc -> sc.failed(e.getMessage())));
        }
    }

    ScanItem match(String owner, int index, LibraryAi.SpineReading r) {
        String author = r.authors() == null || r.authors().isEmpty() ? null : r.authors().getFirst();
        if (r.title() == null || r.title().isBlank()) {
            return new ScanItem(index, r, ScanItem.Status.UNSURE, null, List.of(), null);
        }
        List<BookMetadata> found = metadata.search(r.title(), author, r.publisher()).stream()
                .filter(m -> Similarity.title(r.title(), m.title()) >= MIN_TITLE_MATCH)
                .limit(4)
                .toList();
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
        int position = 0;
        for (ScanItem item : items) {
            Decision d = decisions.stream().filter(x -> x.index() == item.index()).findFirst().orElse(null);
            UUID bookId = item.ownedBookId();
            if (d != null && d.take() && bookId == null) {
                bookId = add(owner, item, d.isbn13()).getId();
                created.add(bookId);
            }
            if (bookId != null) {
                position++;
                if (scan.getShelfId() != null) {
                    books.move(owner, bookId, scan.getShelfId(), scan.getBoardNumber(), position);
                    placed.add(bookId);
                }
            }
        }
        tx.executeWithoutResult(s -> scans.findById(scanId).ifPresent(Scan::accepted));
        return new AcceptResult(created, placed);
    }

    private Book add(String owner, ScanItem item, String chosenIsbn) {
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
            return books.addFromMetadata(owner, chosen, hint);
        }
        return books.addManual(owner, new de.raindancer118.shelved.book.BookInput(r.title(), null, r.authors(), null,
                blank(r.publisher()), null, null, null, null, null, null, null, null), hint);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public static BufferedImage decode(byte[] image) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(image));
            if (img == null) {
                throw new IllegalArgumentException("Bildformat nicht erkannt (JPEG oder PNG)");
            }
            if ((long) img.getWidth() * img.getHeight() > 60_000_000L) {
                throw new IllegalArgumentException("Bild ist zu groß");
            }
            return img;
        } catch (IOException e) {
            throw new IllegalArgumentException("Bild konnte nicht gelesen werden");
        }
    }
}
