package de.raindancer118.shelved.library;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

/** Principal of a password login; stored in the (JDBC) session, hence Serializable. */
public record AccountPrincipal(UUID accountId) implements Principal, Serializable {
    @Override
    public String getName() {
        return "account:" + accountId;
    }
}
