package de.raindancer118.shelved.book;

import de.raindancer118.shelved.metadata.BookMetadata;
import de.raindancer118.shelved.metadata.MetadataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Books that came in without a cover (a shelf photo without catalogue match, a source that was down, an edition only
 * Google knows) get one later: by their ISBN, else by the same work found under title and author. Runs after start.
 */
@Component
public class CoverBackfill {

    private static final Logger log = LoggerFactory.getLogger(CoverBackfill.class);

    private final BookRepository books;
    private final CoverStore covers;
    private final MetadataService metadata;
    private final TransactionTemplate tx;

    public CoverBackfill(BookRepository books, CoverStore covers, MetadataService metadata, TransactionTemplate tx) {
        this.books = books;
        this.covers = covers;
        this.metadata = metadata;
        this.tx = tx;
    }

    /** Returns how many books got a cover. */
    public int fill() {
        List<Book> missing = tx.execute(s -> books.findByCoverFileIsNull());
        int found = 0;
        for (Book b : missing == null ? List.<Book>of() : missing) {
            Optional<String> file = b.getIsbn13() == null ? Optional.empty()
                    : covers.fetch(b.getId(), CoverStore.candidates(b.getIsbn13(), null));
            if (file.isEmpty()) {
                file = sameWork(b).flatMap(m -> covers.fetch(b.getId(), CoverStore.candidates(m.isbn13(), m.coverUrl())));
            }
            if (file.isPresent()) {
                String name = file.get();
                tx.executeWithoutResult(s -> books.findById(b.getId()).filter(x -> x.getCoverFile() == null)
                        .ifPresent(x -> x.setCoverFile(name)));
                found++;
            }
        }
        return found;
    }

    private Optional<BookMetadata> sameWork(Book b) {
        String author = b.getAuthors().isEmpty() ? null : b.getAuthors().getFirst();
        try {
            return metadata.search(b.getTitle(), author, null).stream()
                    .filter(m -> m.isbn13() != null || m.coverUrl() != null)
                    .filter(m -> BookService.sameWork(m.title(), m.authors(), b.getTitle(), author))
                    .findFirst();
        } catch (RuntimeException e) {
            log.debug("No catalogue answer for {}: {}", b.getTitle(), e.getMessage());
            return Optional.empty();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void fillInTheBackground() {
        Thread.ofVirtual().name("cover-backfill").start(() -> {
            int n = fill();
            if (n > 0) {
                log.info("Found covers for {} book(s) that had none", n);
            }
        });
    }
}
