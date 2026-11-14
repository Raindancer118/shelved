package de.raindancer118.shelved.scan;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scan")
public class Scan {

    public enum Mode { SHELF, BOOK }

    public enum Status { PENDING, DONE, FAILED, ACCEPTED }

    @Id
    private UUID id = UUID.randomUUID();
    private String ownerId;
    @Enumerated(EnumType.STRING)
    private Mode mode;
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;
    private UUID shelfId;
    private Integer boardNumber;
    private String error;
    private String resultJson;
    private Instant createdAt = Instant.now();
    private Instant finishedAt;
    private String photoFile;

    protected Scan() {
    }

    public Scan(String ownerId, Mode mode, UUID shelfId, Integer boardNumber) {
        this.ownerId = ownerId;
        this.mode = mode;
        this.shelfId = shelfId;
        this.boardNumber = boardNumber;
    }

    public void done(String resultJson) {
        this.resultJson = resultJson;
        this.status = Status.DONE;
        this.finishedAt = Instant.now();
    }

    public void failed(String error) {
        this.error = error == null ? "Unbekannter Fehler" : error.substring(0, Math.min(1000, error.length()));
        this.status = Status.FAILED;
        this.finishedAt = Instant.now();
    }

    public void accepted() {
        this.status = Status.ACCEPTED;
    }

    public String getPhotoFile() { return photoFile; }
    public void setPhotoFile(String photoFile) { this.photoFile = photoFile; }
    public Instant getFinishedAt() { return finishedAt; }
    public UUID getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public Mode getMode() { return mode; }
    public Status getStatus() { return status; }
    public UUID getShelfId() { return shelfId; }
    public Integer getBoardNumber() { return boardNumber; }
    public String getError() { return error; }
    public String getResultJson() { return resultJson; }
    public Instant getCreatedAt() { return createdAt; }
}
