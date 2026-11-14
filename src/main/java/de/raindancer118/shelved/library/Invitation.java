package de.raindancer118.shelved.library;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitation")
public class Invitation {

    @Id
    private UUID id = UUID.randomUUID();
    private UUID libraryId;
    private String email;
    @Enumerated(EnumType.STRING)
    private Membership.Role role;
    private String tokenHash;
    private UUID invitedBy;
    private Instant expiresAt;
    private Instant acceptedAt;

    protected Invitation() {
    }

    public Invitation(UUID libraryId, String email, Membership.Role role, String tokenHash, UUID invitedBy, Duration validFor) {
        this.libraryId = libraryId;
        this.email = email;
        this.role = role;
        this.tokenHash = tokenHash;
        this.invitedBy = invitedBy;
        this.expiresAt = Instant.now().plus(validFor);
    }

    public boolean open() {
        return acceptedAt == null && Instant.now().isBefore(expiresAt);
    }

    public void accept() {
        this.acceptedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getLibraryId() { return libraryId; }
    public String getEmail() { return email; }
    public Membership.Role getRole() { return role; }
    public UUID getInvitedBy() { return invitedBy; }
    public Instant getExpiresAt() { return expiresAt; }
}
