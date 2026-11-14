package de.raindancer118.shelved.web;

import org.springframework.security.core.Authentication;

final class Owner {

    private Owner() {
    }

    /** OIDC subject (or "local" in dev mode). All data is scoped by it. */
    static String of(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("Nicht angemeldet");
        }
        return auth.getName();
    }
}
