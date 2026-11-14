package de.raindancer118.shelved.placement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlacementPlannerTest {

    record B(String id, int mm) implements PlacementPlanner.Item {
        public int thicknessMm() { return mm; }
    }

    @Test
    void fillsBoardsInOrderAndMovesOnWhenFull() {
        var boards = List.of(
                new PlacementPlanner.Board(1, 1, 100),
                new PlacementPlanner.Board(1, 2, 100),
                new PlacementPlanner.Board(2, 1, 100));
        var books = List.of(new B("a", 40), new B("b", 40), new B("c", 40), new B("d", 70), new B("e", 30));

        var plan = PlacementPlanner.plan(books, boards);

        assertThat(plan.slots()).extracting(s -> s.item().id() + "@" + s.shelf() + "." + s.board() + "." + s.position())
                .containsExactly("a@1.1.1", "b@1.1.2", "c@1.2.1", "d@2.1.1", "e@2.1.2");
        assertThat(plan.overflow()).extracting(PlacementPlanner.Item::id).isEmpty();
        assertThat(plan.freeMm(2, 1)).isZero();
    }

    @Test
    void lastBoardTakesTheRestAsOverflowWhenEverythingIsFull() {
        var boards = List.of(new PlacementPlanner.Board(1, 1, 50));
        var books = List.of(new B("a", 30), new B("b", 30), new B("c", 30));

        var plan = PlacementPlanner.plan(books, boards);

        assertThat(plan.slots()).extracting(s -> s.item().id()).containsExactly("a");
        assertThat(plan.overflow()).extracting(PlacementPlanner.Item::id).containsExactly("b", "c");
    }

    @Test
    void aBookWiderThanAnEmptyBoardStillGetsPlaced() {
        var boards = List.of(new PlacementPlanner.Board(1, 1, 20), new PlacementPlanner.Board(1, 2, 100));
        var plan = PlacementPlanner.plan(List.of(new B("fat", 60)), boards);
        assertThat(plan.slots()).extracting(s -> s.board()).containsExactly(1);
    }

    @Test
    void noBoardsMeansEverythingOverflows() {
        var plan = PlacementPlanner.plan(List.of(new B("a", 10)), List.of());
        assertThat(plan.overflow()).hasSize(1);
    }
}
