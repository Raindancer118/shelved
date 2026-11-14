package de.raindancer118.shelved.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fills shelf boards left to right in the given book order, like you'd put them in by hand. */
public final class PlacementPlanner {

    public interface Item {
        String id();

        int thicknessMm();
    }

    public record Board(int shelf, int board, int widthMm) {
    }

    public record Slot<T extends Item>(T item, int shelf, int board, int position) {
    }

    public record Plan<T extends Item>(List<Slot<T>> slots, List<T> overflow, Map<String, Integer> usedMm,
                                       Map<String, Integer> widthMm) {
        public int freeMm(int shelf, int board) {
            String k = key(shelf, board);
            return widthMm.getOrDefault(k, 0) - usedMm.getOrDefault(k, 0);
        }
    }

    private PlacementPlanner() {
    }

    public static <T extends Item> Plan<T> plan(List<T> ordered, List<Board> boards) {
        List<Slot<T>> slots = new ArrayList<>();
        List<T> overflow = new ArrayList<>();
        Map<String, Integer> used = new HashMap<>();
        Map<String, Integer> widths = new HashMap<>();
        boards.forEach(b -> widths.put(key(b.shelf(), b.board()), b.widthMm()));

        int bi = 0;
        int usedOnBoard = 0;
        int position = 0;
        for (T item : ordered) {
            while (bi < boards.size() && position > 0 && usedOnBoard + item.thicknessMm() > boards.get(bi).widthMm()) {
                bi++;
                usedOnBoard = 0;
                position = 0;
            }
            if (bi >= boards.size()) {
                overflow.add(item);
                continue;
            }
            Board b = boards.get(bi);
            position++;
            usedOnBoard += item.thicknessMm();
            used.merge(key(b.shelf(), b.board()), item.thicknessMm(), Integer::sum);
            slots.add(new Slot<>(item, b.shelf(), b.board(), position));
        }
        return new Plan<>(slots, overflow, used, widths);
    }

    static String key(int shelf, int board) {
        return shelf + "." + board;
    }
}
