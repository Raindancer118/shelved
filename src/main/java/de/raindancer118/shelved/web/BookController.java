package de.raindancer118.shelved.web;

import de.raindancer118.shelved.book.Book;
import de.raindancer118.shelved.book.BookInput;
import de.raindancer118.shelved.book.BookService;
import de.raindancer118.shelved.book.CoverStore;
import de.raindancer118.shelved.book.Isbn;
import de.raindancer118.shelved.book.Names;
import de.raindancer118.shelved.book.NotFoundException;
import de.raindancer118.shelved.metadata.BookMetadata;
import de.raindancer118.shelved.metadata.MetadataService;
import de.raindancer118.shelved.placement.PlacementService;
import de.raindancer118.shelved.shelf.ShelfRepository;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class BookController {

    public record AddByIsbn(String isbn) {
    }

    public record Lookup(String isbn13, List<Views.BookSummary> owned, BookMetadata metadata) {
    }

    private final BookService books;
    private final MetadataService metadata;
    private final CoverStore covers;
    private final ShelfRepository shelves;

    public BookController(BookService books, MetadataService metadata, CoverStore covers, ShelfRepository shelves) {
        this.books = books;
        this.metadata = metadata;
        this.covers = covers;
        this.shelves = shelves;
    }

    Map<UUID, Integer> shelfNumbers(String owner) {
        var list = shelves.findByOwnerIdOrderByPosition(owner);
        return java.util.stream.IntStream.range(0, list.size()).boxed()
                .collect(Collectors.toMap(i -> list.get(i).getId(), i -> i + 1));
    }

    @GetMapping("/books")
    public List<Views.BookSummary> list(Authentication auth, @RequestParam(defaultValue = "author") String sort,
                                        @RequestParam(required = false) String q) {
        String owner = Owner.of(auth);
        var numbers = shelfNumbers(owner);
        Comparator<Book> order = switch (sort) {
            case "title" -> Comparator.comparing(b -> Names.collationKey(b.getTitleSort()));
            case "added" -> Comparator.comparing(Book::getCreatedAt).reversed();
            default -> PlacementService.LIBRARY_ORDER;
        };
        String needle = q == null ? null : Names.collationKey(q);
        return books.all(owner).stream()
                .filter(b -> needle == null || needle.isBlank() || matches(b, needle))
                .sorted(order)
                .map(b -> Views.summary(b, numbers))
                .toList();
    }

    private static boolean matches(Book b, String needle) {
        String digits = needle.replaceAll("[^0-9x]", "");
        if (digits.length() >= 10 && b.getIsbn13() != null) {
            return Isbn.toIsbn13(digits).map(i -> i.equals(b.getIsbn13())).orElse(false);
        }
        String hay = Names.collationKey(String.join(" ", b.getTitle(), String.valueOf(b.getSubtitle()),
                String.join(" ", b.getAuthors()), String.valueOf(b.getSeriesName()), String.valueOf(b.getIsbn13())));
        return java.util.Arrays.stream(needle.split("\\s+")).allMatch(hay::contains);
    }

    @GetMapping("/books/{id}")
    public Views.BookDetail get(Authentication auth, @PathVariable UUID id) {
        String owner = Owner.of(auth);
        return Views.detail(books.get(owner, id), shelfNumbers(owner));
    }

    @PostMapping("/books/isbn")
    @ResponseStatus(HttpStatus.CREATED)
    public Views.BookDetail addByIsbn(Authentication auth, @RequestBody AddByIsbn req) {
        String owner = Owner.of(auth);
        String isbn = Isbn.toIsbn13(req.isbn()).orElseThrow(() -> new IllegalArgumentException("Keine gültige ISBN"));
        BookMetadata m = metadata.byIsbn(isbn).orElseThrow(() -> new NotFoundException("Buch zu dieser ISBN"));
        return Views.detail(books.addFromMetadata(owner, m, BookService.SeriesHint.none()), shelfNumbers(owner));
    }

    @PostMapping("/books")
    @ResponseStatus(HttpStatus.CREATED)
    public Views.BookDetail addManual(Authentication auth, @Valid @RequestBody BookInput in) {
        String owner = Owner.of(auth);
        return Views.detail(books.addManual(owner, in, BookService.SeriesHint.none()), shelfNumbers(owner));
    }

    @PutMapping("/books/{id}")
    public Views.BookDetail update(Authentication auth, @PathVariable UUID id, @Valid @RequestBody BookInput in) {
        String owner = Owner.of(auth);
        return Views.detail(books.update(owner, id, in), shelfNumbers(owner));
    }

    @DeleteMapping("/books/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable UUID id) {
        books.delete(Owner.of(auth), id);
    }

    @GetMapping("/books/{id}/cover")
    public ResponseEntity<FileSystemResource> cover(Authentication auth, @PathVariable UUID id) {
        Book b = books.get(Owner.of(auth), id);
        var path = covers.path(b.getCoverFile()).orElseThrow(() -> new NotFoundException("Cover"));
        MediaType type = path.toString().endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .body(new FileSystemResource(path));
    }

    @PutMapping(value = "/books/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Views.BookDetail uploadCover(Authentication auth, @PathVariable UUID id, @RequestParam("image") MultipartFile image) throws IOException {
        String owner = Owner.of(auth);
        String type = image.getContentType() == null ? "" : image.getContentType();
        if (!type.equals("image/jpeg") && !type.equals("image/png")) {
            throw new IllegalArgumentException("Nur JPEG oder PNG");
        }
        return Views.detail(books.replaceCover(owner, id, image.getBytes(), type.endsWith("png") ? "png" : "jpg"), shelfNumbers(owner));
    }

    /** "Hab ich das schon?" for a scanned barcode, plus catalogue data to add it right away. */
    @GetMapping("/lookup")
    public Lookup lookup(Authentication auth, @RequestParam String isbn) {
        String owner = Owner.of(auth);
        String isbn13 = Isbn.toIsbn13(isbn).orElseThrow(() -> new IllegalArgumentException("Keine gültige ISBN"));
        var numbers = shelfNumbers(owner);
        BookMetadata m = metadata.byIsbn(isbn13).orElse(null);
        var owned = books.findOwned(owner, isbn13, m == null ? null : m.title(), m == null || m.authors().isEmpty() ? null : m.authors().getFirst());
        return new Lookup(isbn13, owned.stream().map(b -> Views.summary(b, numbers)).toList(), m);
    }

    /** Catalogue search for adding a book by hand. */
    @GetMapping("/catalog")
    public List<BookMetadata> catalog(@RequestParam String title, @RequestParam(required = false) String author) {
        if (title.isBlank()) {
            return List.of();
        }
        return metadata.search(title, author, null).stream().limit(12).toList();
    }

    @PostMapping("/books/from-catalog")
    @ResponseStatus(HttpStatus.CREATED)
    public Views.BookDetail addFromCatalog(Authentication auth, @RequestBody BookMetadata m) {
        String owner = Owner.of(auth);
        if (m.title() == null || m.title().isBlank()) {
            throw new IllegalArgumentException("Titel fehlt");
        }
        // prefer fresh data for ISBN-identified books instead of trusting the client copy
        BookMetadata data = m.isbn13() == null ? m : metadata.byIsbn(m.isbn13()).orElse(m);
        return Views.detail(books.addFromMetadata(owner, data, BookService.SeriesHint.none()), shelfNumbers(owner));
    }
}
