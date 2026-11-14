package de.raindancer118.shelved.library;

import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Who is asking and for which library, resolved once per request by {@link TenantFilter}. */
public record CurrentUser(UUID accountId, UUID libraryId, Membership.Role role) {

    static final String ATTRIBUTE = CurrentUser.class.getName();

    public static CurrentUser get() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        Object u = attrs == null ? null : attrs.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (u == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nicht angemeldet");
        }
        return (CurrentUser) u;
    }

    /** Books, shelves and scans are stored under the library id. */
    public String owner() {
        return libraryId.toString();
    }

    public void requireManager() {
        if (!role.canManage()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur für die Leitung der Bibliothek");
        }
    }
}
