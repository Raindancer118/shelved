package de.raindancer118.shelved.account;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Single-use link token. Only its SHA-256 is stored, so a database dump doesn't open accounts. */
@Entity
@Table(name = "account_token")
public class AccountToken {

    public enum Kind { VERIFY, RESET, CHANGE_EMAIL }

    @Id
    private UUID id = UUID.randomUUID();
    private UUID accountId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private String tokenHash;
    private String payload;
    private Instant expiresAt;
    private Instant usedAt;

    protected AccountToken() {
    }

    public AccountToken(UUID accountId, Kind kind, String tokenHash, String payload, Duration validFor) {
        this.accountId = accountId;
        this.kind = kind;
        this.tokenHash = tokenHash;
        this.payload = payload;
        this.expiresAt = Instant.now().plus(validFor);
    }

    public boolean usable() {
        return usedAt == null && Instant.now().isBefore(expiresAt);
    }

    public void use() {
        this.usedAt = Instant.now();
    }

    public UUID getAccountId() { return accountId; }
    public Kind getKind() { return kind; }
    public String getPayload() { return payload; }
}
