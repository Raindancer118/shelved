package de.raindancer118.shelved.placement;

import de.raindancer118.shelved.book.Book;
import de.raindancer118.shelved.book.BookRepository;
import de.raindancer118.shelved.book.Names;
import de.raindancer118.shelved.shelf.Shelf;
import de.raindancer118.shelved.shelf.ShelfRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PlacementService {

    public enum Status { OK, MOVE, NEW }

    public record PlacedBook(Book book, int position, int thicknessMm, Status status, String from) {
    }

    public record BoardPlan(UUID shelfId, String shelfName, int shelfNumber, int board, int widthMm, int usedMm,
                            List<PlacedBook> books) {
    }

    public record Plan(List<BoardPlan> boards, List<Book> overflow, int toMove, int toPlace) {
    }

    /** Library order: author surname, then year (keeps an author's books in publication order), then title. */
    public static final Comparator<Book> LIBRARY_ORDER = Comparator
            .comparing((Book b) -> b.getAuthorSort() == null ? "￿" : Names.collationKey(b.getAuthorSort()))
            .thenComparing(Book::getPublishedYear, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(b -> Names.collationKey(b.getTitleSort()));

    private final BookRepository books;
    private final ShelfRepository shelves;

    public PlacementService(BookRepository books, ShelfRepository shelves) {
        this.books = books;
        this.shelves = shelves;
    }

    record Item(Book book, int thicknessMm) implements PlacementPlanner.Item {
        public String id() {
            return book.getId().toString();
        }
    }

    @Transactional(readOnly = true)
    public Plan plan(String owner) {
        List<Shelf> shelfList = shelves.findByOwnerIdOrderByPosition(owner);
        Map<UUID, Integer> shelfNumber = new LinkedHashMap<>();
        List<PlacementPlanner.Board> boards = new ArrayList<>();
        for (int s = 0; s < shelfList.size(); s++) {
            Shelf shelf = shelfList.get(s);
            shelfNumber.put(shelf.getId(), s + 1);
            List<Integer> widths = shelf.getBoardWidthsMm();
            for (int i = 0; i < widths.size(); i++) {
                boards.add(new PlacementPlanner.Board(s + 1, i + 1, widths.get(i)));
            }
        }
        List<Item> ordered = books.findByOwnerId(owner).stream()
                .sorted(LIBRARY_ORDER)
                .map(b -> new Item(b, ThicknessEstimator.estimateMm(b) * b.getCopies()))
                .toList();
        var plan = PlacementPlanner.plan(ordered, boards);

        Map<String, List<PlacedBook>> byBoard = new LinkedHashMap<>();
        int toMove = 0;
        int toPlace = 0;
        for (var slot : plan.slots()) {
            Book b = slot.item().book();
            Status status;
            String from = null;
            if (b.getShelfId() == null || !shelfNumber.containsKey(b.getShelfId())) {
                status = Status.NEW;
                toPlace++;
            } else if (shelfNumber.get(b.getShelfId()) == slot.shelf() && b.getBoardNumber() != null && b.getBoardNumber() == slot.board()) {
                status = Status.OK;
            } else {
                status = Status.MOVE;
                from = label(shelfNumber.get(b.getShelfId()), b.getBoardNumber(), b.getBoardPosition());
                toMove++;
            }
            byBoard.computeIfAbsent(slot.shelf() + "." + slot.board(), k -> new ArrayList<>())
                    .add(new PlacedBook(b, slot.position(), slot.item().thicknessMm(), status, from));
        }
        Map<Integer, Shelf> shelfByNumber = shelfList.stream().collect(Collectors.toMap(s -> shelfNumber.get(s.getId()), Function.identity()));
        List<BoardPlan> boardPlans = boards.stream().map(bd -> {
            Shelf shelf = shelfByNumber.get(bd.shelf());
            List<PlacedBook> placed = byBoard.getOrDefault(bd.shelf() + "." + bd.board(), List.of());
            int used = placed.stream().mapToInt(PlacedBook::thicknessMm).sum();
            return new BoardPlan(shelf.getId(), shelf.getName(), bd.shelf(), bd.board(), bd.widthMm(), used, placed);
        }).toList();
        return new Plan(boardPlans, plan.overflow().stream().map(Item::book).toList(), toMove, toPlace);
    }

    public static String label(Integer shelf, Integer board, Integer position) {
        if (shelf == null) {
            return null;
        }
        StringBuilder s = new StringBuilder("R").append(shelf);
        if (board != null) {
            s.append(" · B").append(board);
            if (position != null) {
                s.append(" · ").append(String.format("%02d", position));
            }
        }
        return s.toString();
    }
}
