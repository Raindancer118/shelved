package de.raindancer118.shelved.stats;

import de.raindancer118.shelved.book.Book;
import de.raindancer118.shelved.book.BookRepository;
import de.raindancer118.shelved.pricing.UsedPriceEstimator;
import de.raindancer118.shelved.series.SeriesService;
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

    public record Stats(long books, long withPrice, long listValueCents, long usedValueCents, int shelves,
                        int series, int completeSeries, int missingVolumes, long pages, List<AuthorCount> topAuthors) {
    }

    private final BookRepository books;
    private final ShelfRepository shelves;
    private final SeriesService series;

    public StatsService(BookRepository books, ShelfRepository shelves, SeriesService series) {
        this.books = books;
        this.shelves = shelves;
        this.series = series;
    }

    @Transactional(readOnly = true)
    public Stats stats(String owner) {
        List<Book> all = books.findByOwnerId(owner);
        int year = Year.now().getValue();
        long list = all.stream().map(Book::getListPriceCents).filter(p -> p != null).mapToLong(Integer::longValue).sum();
        long used = all.stream()
                .map(b -> UsedPriceEstimator.estimateCents(b.getListPriceCents(), b.getFormat(), b.getPublishedYear(), year))
                .filter(p -> p != null).mapToLong(Integer::longValue).sum();
        long withPrice = all.stream().filter(b -> b.getListPriceCents() != null).count();
        var seriesViews = series.overview(owner);
        Map<String, Long> byAuthor = all.stream().filter(b -> !b.getAuthors().isEmpty())
                .collect(Collectors.groupingBy(b -> b.getAuthors().getFirst(), Collectors.counting()));
        List<AuthorCount> top = byAuthor.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(5).map(e -> new AuthorCount(e.getKey(), e.getValue())).toList();
        return new Stats(all.size(), withPrice, list, used, shelves.findByOwnerIdOrderByPosition(owner).size(),
                seriesViews.size(), (int) seriesViews.stream().filter(v -> v.missingCount() == 0).count(),
                seriesViews.stream().mapToInt(SeriesService.SeriesView::missingCount).sum(),
                all.stream().map(Book::getPageCount).filter(p -> p != null).mapToLong(Integer::longValue).sum(),
                top);
    }

}
