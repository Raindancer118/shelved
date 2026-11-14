package de.raindancer118.shelved.library;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * Maps the login (password account, SSO subject or the dev user) to an account and its current library,
 * and keeps read-only members from changing anything.
 */
public class TenantFilter extends OncePerRequestFilter {

    public static final String SESSION_LIBRARY = "shelved.library";
    private static final Set<String> READ = Set.of("GET", "HEAD", "OPTIONS");

    private final LibraryService libraries;

    public TenantFilter(LibraryService libraries) {
        this.libraries = libraries;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        UUID accountId;
        if (auth.getPrincipal() instanceof AccountPrincipal p) {
            accountId = p.accountId();
        } else {
            String email = auth.getPrincipal() instanceof OidcUser u && Boolean.TRUE.equals(u.getEmailVerified()) ? u.getEmail() : null;
            String name = auth.getPrincipal() instanceof OidcUser u ? (u.getGivenName() != null ? u.getGivenName() : u.getPreferredUsername()) : null;
            accountId = libraries.accountForSubject(auth.getName(), email, name);
        }
        if (accountId == null) {
            // the account was deleted while the session lived on
            request.getSession().invalidate();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        HttpSession session = request.getSession(false);
        UUID preferred = session == null ? null : (UUID) session.getAttribute(SESSION_LIBRARY);
        Membership m = libraries.currentMembership(accountId, preferred);
        if (session != null && !m.getLibraryId().equals(preferred)) {
            session.setAttribute(SESSION_LIBRARY, m.getLibraryId());
        }
        CurrentUser user = new CurrentUser(accountId, m.getLibraryId(), m.getRole());
        request.setAttribute(CurrentUser.ATTRIBUTE, user);

        String path = request.getRequestURI();
        if (!m.getRole().canWrite() && !READ.contains(request.getMethod()) && path.startsWith("/api/")
                && !path.startsWith("/api/account") && !path.startsWith("/api/auth/") && !path.startsWith("/api/libraries")
                && !path.startsWith("/api/invitations/")) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Du kannst in dieser Bibliothek nur lesen\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
