package de.raindancer118.shelved.account;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A person. Logs in with e-mail and password, or through the SSO portal (oidcSubject), or both. */
@Entity
@Table(name = "account")
public class Account {

    @Id
    private UUID id = UUID.randomUUID();
    private String email;
    private String displayName;
    private String passwordHash;
    private boolean emailVerified;
    private String oidcSubject;
    private Instant createdAt = Instant.now();

    protected Account() {
    }

    public static Account withPassword(String email, String displayName, String passwordHash) {
        Account a = new Account();
        a.email = email;
        a.displayName = displayName;
        a.passwordHash = passwordHash;
        return a;
    }

    public static Account fromSso(String subject, String email, String displayName) {
        Account a = new Account();
        a.oidcSubject = subject;
        a.email = email;
        a.displayName = displayName;
        a.emailVerified = true;
        return a;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isEmailVerified() { return emailVerified; }
    public void verified() { this.emailVerified = true; }
    public String getOidcSubject() { return oidcSubject; }
    public Instant getCreatedAt() { return createdAt; }
}
