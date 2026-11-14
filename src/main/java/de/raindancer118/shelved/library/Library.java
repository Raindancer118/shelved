package de.raindancer118.shelved.library;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A collection of books with its members. Books, shelves and scans carry its id in owner_id. */
@Entity
@Table(name = "library")
public class Library {

    public enum Kind { PRIVATE, PUBLIC, COMPANY }

    @Id
    private UUID id = UUID.randomUUID();
    private String name;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private String slug;
    private Instant createdAt = Instant.now();

    protected Library() {
    }

    public Library(String name, Kind kind) {
        this.name = name;
        this.kind = kind;
        this.slug = "b-" + id.toString().replace("-", "").substring(0, 12);
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public String getSlug() { return slug; }
    public Instant getCreatedAt() { return createdAt; }
}
