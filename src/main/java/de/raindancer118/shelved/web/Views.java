package de.raindancer118.shelved.web;

import de.raindancer118.shelved.book.Book;
import de.raindancer118.shelved.book.BookFormat;
import de.raindancer118.shelved.placement.PlacementService;
import de.raindancer118.shelved.placement.ThicknessEstimator;
import de.raindancer118.shelved.pricing.UsedPriceEstimator;

import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** JSON shapes the frontend sees. */
public final class Views {

    private Views() {
    }

    public record BookSummary(UUID id, String isbn13, String title, List<String> authors, String authorSort,
                              String cover, String location, int copies, Instant createdAt) {
    }

    public record BookDetail(UUID id, String isbn13, String title, String subtitle, List<String> authors,
                             String publisher, Integer publishedYear, Integer pageCount, Integer heightMm,
                             Integer thicknessMm, int estimatedThicknessMm, ThicknessEstimator.Source thicknessSource, BookFormat format, String language,
                             String description,
                             Integer listPriceCents, Integer usedPriceCents, Integer buybackPriceCents, String cover, UUID shelfId,
                             Integer boardNumber, Integer boardPosition, String location, String metadataSource,
                             int copies, Instant createdAt, String backCover, String spine, String edgeColor, String edgePhoto) {
    }

    public static String cover(Book b) {
        return b.getCoverFile() == null ? null : "/api/books/" + b.getId() + "/cover?v=f2" + b.getCoverFile().hashCode(); // f2: cut front without a Farbschnitt strip since 0.16
    }

    static BookSummary summary(Book b, Map<UUID, Integer> shelfNumbers) {
        return new BookSummary(b.getId(), b.getIsbn13(), b.getTitle(), b.getAuthors(), b.getAuthorSort(),
                cover(b), location(b, shelfNumbers), b.getCopies(), b.getCreatedAt());
    }

    static BookDetail detail(Book b, Map<UUID, Integer> shelfNumbers) {
        return new BookDetail(b.getId(), b.getIsbn13(), b.getTitle(), b.getSubtitle(), b.getAuthors(), b.getPublisher(),
                b.getPublishedYear(), b.getPageCount(), b.getHeightMm(), b.getThicknessMm(),
                ThicknessEstimator.estimateMm(b), ThicknessEstimator.source(b), b.getFormat(),
                b.getLanguage(), de.raindancer118.shelved.metadata.Texts.blurb(b.getDescription()), b.getListPriceCents(),
                UsedPriceEstimator.privateCents(b.getListPriceCents(), b.getFormat(), b.getPublishedYear(), Year.now().getValue()),
                UsedPriceEstimator.buybackCents(b.getListPriceCents(), b.getFormat(), b.getPublishedYear(), Year.now().getValue()),
                cover(b), b.getShelfId(), b.getBoardNumber(), b.getBoardPosition(), location(b, shelfNumbers),
                b.getMetadataSource(), b.getCopies(), b.getCreatedAt(),
                photo(b, Book.Side.BACK), photo(b, Book.Side.SPINE), b.getEdgeColor(), edgePhoto(b));
    }

    /** Own photo first; without one and without a colour chosen by hand, the page block of the publisher's mockup. */
    private static String edgePhoto(Book b) {
        String own = photo(b, Book.Side.EDGE);
        if (own != null || b.getEdgeColor() != null || b.getCoverFile() == null) {
            return own;
        }
        return "/api/books/" + b.getId() + "/edge-photo?v=m" + b.getCoverFile().hashCode();
    }

    private static String photo(Book b, Book.Side side) {
        String file = b.getPhoto(side);
        return file == null ? null : "/api/books/" + b.getId() + "/" + side.path + "?v=" + file.hashCode();
    }


    static String location(Book b, Map<UUID, Integer> shelfNumbers) {
        if (b.getShelfId() == null) {
            return null;
        }
        return PlacementService.label(shelfNumbers.get(b.getShelfId()), b.getBoardNumber(), b.getBoardPosition());
    }
}
