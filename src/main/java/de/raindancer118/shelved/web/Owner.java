package de.raindancer118.shelved.web;

import de.raindancer118.shelved.library.CurrentUser;
import org.springframework.security.core.Authentication;

public final class Owner {

    private Owner() {
    }

    /** Id of the library the current user works in; all books, shelves and scans are scoped by it. */
    public static String of(Authentication auth) {
        return CurrentUser.get().owner();
    }
}
