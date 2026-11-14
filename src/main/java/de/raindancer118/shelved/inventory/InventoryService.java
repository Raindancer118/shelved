package de.raindancer118.shelved.inventory;

import de.raindancer118.shelved.book.Book;
import de.raindancer118.shelved.book.BookFormat;
import de.raindancer118.shelved.book.BookRepository;
import de.raindancer118.shelved.book.Names;
import de.raindancer118.shelved.placement.PlacementService;
import de.raindancer118.shelved.scan.Scan;
import de.raindancer118.shelved.scan.ScanService;
import de.raindancer118.shelved.shelf.Shelf;
import de.raindancer118.shelved.shelf.ShelfRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The library as a household insurer wants it: every book with its replacement value (today's list price,
 * which is what household insurance pays), where it stands, and the shelf photos as proof of ownership.
 */
@Service
public class InventoryService {

    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    /** Fallback when no book of the collection has a price at all. */
    static final int DEFAULT_PRICE_CENTS = 1200;

    public record Row(int nr, Book book, String location, int copies, Integer listPriceCents, int unitCents,
                      boolean estimated) {
        public long valueCents() {
            return (long) unitCents * copies;
        }
    }

    public record Photo(UUID scanId, String label, Instant takenAt) {
    }

    public record Inventory(String ownerName, LocalDate date, List<Row> rows, List<Photo> photos) {
        public long copies() {
            return rows.stream().mapToLong(Row::copies).sum();
        }

        public long totalCents() {
            return rows.stream().mapToLong(Row::valueCents).sum();
        }

        public long estimatedCents() {
            return rows.stream().filter(Row::estimated).mapToLong(Row::valueCents).sum();
        }

        public long estimatedBooks() {
            return rows.stream().filter(Row::estimated).count();
        }
    }

    private final BookRepository books;
    private final ShelfRepository shelves;
    private final ScanService scans;

    public InventoryService(BookRepository books, ShelfRepository shelves, ScanService scans) {
        this.books = books;
        this.shelves = shelves;
        this.scans = scans;
    }

    @Transactional(readOnly = true)
    public Inventory build(String owner, String ownerName) {
        List<Shelf> shelfList = shelves.findByOwnerIdOrderByPosition(owner);
        Map<UUID, Integer> numbers = IntStream.range(0, shelfList.size()).boxed()
                .collect(Collectors.toMap(i -> shelfList.get(i).getId(), i -> i + 1));
        Map<UUID, String> names = shelfList.stream().collect(Collectors.toMap(Shelf::getId, Shelf::getName));

        List<Book> all = new ArrayList<>(books.findByOwnerId(owner));
        // shelf order first, as one walks along the shelves; books without a place after that, by author
        all.sort(Comparator.comparing((Book b) -> numbers.getOrDefault(b.getShelfId(), Integer.MAX_VALUE))
                .thenComparing(Book::getBoardNumber, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Book::getBoardPosition, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(b -> Names.collationKey(Objects.toString(b.getAuthorSort(), "")))
                .thenComparing(b -> Names.collationKey(b.getTitleSort())));

        Function<BookFormat, Integer> estimate = medianPrices(all);
        List<Row> rows = new ArrayList<>();
        for (Book b : all) {
            Integer price = b.getListPriceCents() != null && b.getListPriceCents() > 0 ? b.getListPriceCents() : null;
            String location = b.getShelfId() == null ? null
                    : PlacementService.label(numbers.get(b.getShelfId()), b.getBoardNumber(), b.getBoardPosition());
            rows.add(new Row(rows.size() + 1, b, location, b.getCopies(), price,
                    price != null ? price : estimate.apply(b.getFormat()), price == null));
        }

        List<Photo> photos = scans.shelfPhotos(owner).stream()
                .map(s -> new Photo(s.getId(), photoLabel(s, names, numbers), s.getCreatedAt()))
                .toList();
        return new Inventory(ownerName, LocalDate.now(BERLIN), rows, photos);
    }

    private static String photoLabel(Scan s, Map<UUID, String> names, Map<UUID, Integer> numbers) {
        String date = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy").format(s.getCreatedAt().atZone(BERLIN));
        if (s.getShelfId() == null || !names.containsKey(s.getShelfId())) {
            return "Regalfoto vom " + date;
        }
        return "R" + numbers.get(s.getShelfId()) + " " + names.get(s.getShelfId())
                + (s.getBoardNumber() == null ? "" : ", ab Boden " + s.getBoardNumber()) + " · " + date;
    }

    /** Price of a book without one: median of the same format in this collection, else of all, else 12 €. */
    static Function<BookFormat, Integer> medianPrices(List<Book> all) {
        Map<BookFormat, List<Integer>> byFormat = all.stream()
                .filter(b -> b.getListPriceCents() != null && b.getListPriceCents() > 0 && b.getFormat() != null)
                .collect(Collectors.groupingBy(Book::getFormat, Collectors.mapping(Book::getListPriceCents, Collectors.toList())));
        Integer overall = median(all.stream().map(Book::getListPriceCents).filter(p -> p != null && p > 0).toList());
        return format -> {
            Integer m = format == null ? null : median(byFormat.getOrDefault(format, List.of()));
            return m != null ? m : overall != null ? overall : DEFAULT_PRICE_CENTS;
        };
    }

    static Integer median(List<Integer> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Integer> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
