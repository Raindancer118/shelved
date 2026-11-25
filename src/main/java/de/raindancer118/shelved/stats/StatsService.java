package de.raindancer118.shelved.stats;

import de.raindancer118.shelved.book.Book;
import de.raindancer118.shelved.book.BookRepository;
import de.raindancer118.shelved.pricing.UsedPriceEstimator;
import de.raindancer118.shelved.shelf.ShelfRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StatsService {

    public record AuthorCount(String author, long books) {
    }

    public record Stats(long books, long withPrice, long listValueCents, long usedValueCents, long buybackValueCents, int shelves,
                        long pages, List<AuthorCount> topAuthors) {
    }

    private final BookRepository books;
    private final ShelfRepository shelves;

    public StatsService(BookRepository books, ShelfRepository shelves) {
        this.books = books;
        this.shelves = shelves;
    }

    @Transactional(readOnly = true)
    public Stats stats(String owner) {
        List<Book> all = books.findByOwnerId(owner);
        int year = Year.now().getValue();
        long list = all.stream().filter(b -> b.getListPriceCents() != null).mapToLong(b -> (long) b.getListPriceCents() * b.getCopies()).sum();
        long used = 0;
        long buyback = 0;
        for (Book b : all) {
            UsedPriceEstimator.UsedValue v = UsedPriceEstimator.estimate(b.getListPriceCents(), b.getFormat(), b.getPublishedYear(), year);
            if (v != null) {
                used += (long) v.privateCents() * b.getCopies();
                buyback += (long) v.buybackCents() * b.getCopies();
            }
        }
        long withPrice = all.stream().filter(b -> b.getListPriceCents() != null).count();
        Map<String, Long> byAuthor = all.stream().filter(b -> !b.getAuthors().isEmpty())
                .collect(Collectors.groupingBy(b -> b.getAuthors().getFirst(), Collectors.counting()));
        List<AuthorCount> top = byAuthor.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(5).map(e -> new AuthorCount(e.getKey(), e.getValue())).toList();
        return new Stats(all.stream().mapToLong(Book::getCopies).sum(), withPrice, list, used, buyback, shelves.findByOwnerIdOrderByPosition(owner).size(),
                all.stream().map(Book::getPageCount).filter(p -> p != null).mapToLong(Integer::longValue).sum(),
                top);
    }

}
