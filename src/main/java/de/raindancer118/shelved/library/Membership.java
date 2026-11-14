package de.raindancer118.shelved.library;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "membership")
public class Membership {

    /** OWNER and ADMIN manage members; STAFF works with the stock; VIEWER only reads. */
    public enum Role {
        OWNER, ADMIN, STAFF, VIEWER;

        public boolean canManage() {
            return this == OWNER || this == ADMIN;
        }

        public boolean canWrite() {
            return this != VIEWER;
        }
    }

    @Id
    private UUID id = UUID.randomUUID();
    private UUID libraryId;
    private UUID accountId;
    @Enumerated(EnumType.STRING)
    private Role role;
    private Instant createdAt = Instant.now();

    protected Membership() {
    }

    public Membership(UUID libraryId, UUID accountId, Role role) {
        this.libraryId = libraryId;
        this.accountId = accountId;
        this.role = role;
    }

    public UUID getLibraryId() { return libraryId; }
    public UUID getAccountId() { return accountId; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public Instant getCreatedAt() { return createdAt; }
}
