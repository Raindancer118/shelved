package de.raindancer118.shelved.account;

import com.fasterxml.jackson.annotation.JsonIgnore;
import de.raindancer118.shelved.book.Book;
import de.raindancer118.shelved.book.BookRepository;
import de.raindancer118.shelved.library.Library;
import de.raindancer118.shelved.library.LibraryService;
import de.raindancer118.shelved.library.Membership;
import de.raindancer118.shelved.library.MembershipRepository;
import de.raindancer118.shelved.placement.PlacementService;
import de.raindancer118.shelved.scan.ScanRepository;
import de.raindancer118.shelved.shelf.Shelf;
import de.raindancer118.shelved.shelf.ShelfRepository;
import de.raindancer118.shelved.wishlist.WishRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Everything shelved keeps about a person (Art. 15 GDPR): the account, and the libraries they belong to with their
 * books, wishes, shelves and photos. Other members of a shared library are not listed: that is their data.
 */
@Component
public class DataExport {

    public record Export(Instant createdAt, Person person, List<LibraryPart> libraries) {
        public int books() {
            return libraries.stream().mapToInt(l -> l.books().stream().mapToInt(BookEntry::copies).sum()).sum();
        }

        public int wishes() {
            return libraries.stream().mapToInt(l -> l.wishes().size()).sum();
        }

        public int shelves() {
            return libraries.stream().mapToInt(l -> l.shelves().size()).sum();
        }
    }

    public record Person(String name, String email, boolean emailConfirmed, Instant accountCreated, boolean portalLogin,
                         boolean passwordSet) {
    }

    public record LibraryPart(String name, Library.Kind kind, Membership.Role role, Instant memberSince, List<BookEntry> books,
                              List<WishEntry> wishes, List<ShelfEntry> shelves, long scans, long shelfPhotos) {
    }

    public record BookEntry(String title, String subtitle, List<String> authors, String isbn13, String publisher, Integer year,
                            Integer pages, String format, String location, int copies, Integer listPriceCents, Instant added,
                            @JsonIgnore String coverFile) {
    }

    public record WishEntry(String title, List<String> authors, String isbn13, String publisher, Integer listPriceCents,
                            Instant added, @JsonIgnore String coverFile) {
    }

    public record ShelfEntry(String name, List<Integer> boardWidthsMm) {
    }

    private final MembershipRepository memberships;
    private final LibraryService libraries;
    private final BookRepository books;
    private final WishRepository wishes;
    private final ShelfRepository shelves;
    private final ScanRepository scans;

    public DataExport(MembershipRepository memberships, LibraryService libraries, BookRepository books, WishRepository wishes,
                      ShelfRepository shelves, ScanRepository scans) {
        this.memberships = memberships;
        this.libraries = libraries;
        this.books = books;
        this.wishes = wishes;
        this.shelves = shelves;
        this.scans = scans;
    }

    @Transactional(readOnly = true)
    public Export collect(Account a) {
        Person person = new Person(a.getDisplayName(), a.getEmail(), a.isEmailVerified(), a.getCreatedAt(),
                a.getOidcSubject() != null, a.getPasswordHash() != null);
        List<LibraryPart> parts = memberships.findByAccountIdOrderByCreatedAt(a.getId()).stream()
                .map(this::part)
                .toList();
        return new Export(Instant.now(), person, parts);
    }

    private LibraryPart part(Membership m) {
        Library lib = libraries.library(m.getLibraryId());
        String owner = lib.getId().toString();
        List<Shelf> shelfList = shelves.findByOwnerIdOrderByPosition(owner);
        Map<UUID, Integer> numbers = IntStream.range(0, shelfList.size()).boxed()
                .collect(Collectors.toMap(i -> shelfList.get(i).getId(), i -> i + 1));
        List<BookEntry> bookEntries = books.findByOwnerId(owner).stream()
                .sorted(PlacementService.LIBRARY_ORDER)
                .map(b -> book(b, numbers))
                .toList();
        List<WishEntry> wishEntries = wishes.findByOwnerIdOrderByCreatedAtDesc(owner).stream()
                .map(w -> new WishEntry(w.getTitle(), w.getAuthors(), w.getIsbn13(), w.getPublisher(), w.getListPriceCents(),
                        w.getCreatedAt(), w.getCoverFile()))
                .toList();
        List<ShelfEntry> shelfEntries = shelfList.stream().map(s -> new ShelfEntry(s.getName(), s.getBoardWidthsMm())).toList();
        return new LibraryPart(lib.getName(), lib.getKind(), m.getRole(), m.getCreatedAt(), bookEntries, wishEntries, shelfEntries,
                scans.countByOwnerId(owner), scans.countByOwnerIdAndPhotoFileNotNull(owner));
    }

    private static BookEntry book(Book b, Map<UUID, Integer> shelfNumbers) {
        String location = b.getShelfId() == null ? null
                : PlacementService.label(shelfNumbers.get(b.getShelfId()), b.getBoardNumber(), b.getBoardPosition());
        return new BookEntry(b.getTitle(), b.getSubtitle(), b.getAuthors(), b.getIsbn13(), b.getPublisher(), b.getPublishedYear(),
                b.getPageCount(), b.getFormat() == null ? null : b.getFormat().name(), location, b.getCopies(), b.getListPriceCents(),
                b.getCreatedAt(), b.getCoverFile());
    }
}
