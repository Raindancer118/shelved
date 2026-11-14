package de.raindancer118.shelved.shelf;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "shelf")
public class Shelf {

    @Id
    private UUID id = UUID.randomUUID();
    @Column(nullable = false)
    private String ownerId;
    @Column(nullable = false)
    private String name;
    private int position;
    /** Top board first. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "shelf_board", joinColumns = @JoinColumn(name = "shelf_id"))
    @OrderColumn(name = "board_index")
    private List<Board> boards = new ArrayList<>();
    private Instant createdAt;

    protected Shelf() {
    }

    public Shelf(String ownerId, String name, int position, List<Board> boards) {
        this.ownerId = ownerId;
        this.name = name;
        this.position = position;
        setBoards(boards);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public List<Board> getBoards() { return boards; }
    public List<Integer> getBoardWidthsMm() { return boards.stream().map(Board::getWidthMm).toList(); }
    public void setBoards(List<Board> newBoards) {
        this.boards.clear();
        this.boards.addAll(newBoards);
    }

    /** Only replaces a width that was a guess; a measured width stays. Board numbers start at 1. */
    public boolean applyEstimate(int boardNumber, int widthMm) {
        Board b = boards.get(boardNumber - 1);
        if (!b.isEstimated()) {
            return false;
        }
        boards.set(boardNumber - 1, new Board(widthMm, true));
        return true;
    }
}
