package de.raindancer118.shelved.library;

import de.raindancer118.shelved.account.Account;
import de.raindancer118.shelved.account.AccountRepository;
import de.raindancer118.shelved.account.Mailer;
import de.raindancer118.shelved.account.Tokens;
import de.raindancer118.shelved.book.CoverStore;
import de.raindancer118.shelved.config.ShelvedProperties;
import de.raindancer118.shelved.scan.PhotoStore;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class LibraryService {

    static final Duration INVITATION_VALID = Duration.ofDays(14);

    public record LibraryView(UUID id, String name, Library.Kind kind, Membership.Role role, boolean current) {
    }

    public record MemberView(UUID accountId, String name, String email, Membership.Role role, java.time.Instant since) {
    }

    public record InvitationView(UUID id, String email, Membership.Role role, java.time.Instant expiresAt) {
    }

    public record InvitationInfo(String library, String invitedBy, String email, Membership.Role role) {
    }

    private final AccountRepository accounts;
    private final LibraryRepository libraries;
    private final MembershipRepository memberships;
    private final InvitationRepository invitations;
    private final Mailer mailer;
    private final ShelvedProperties props;
    private final JdbcTemplate jdbc;
    private final CoverStore covers;
    private final PhotoStore photos;

    public LibraryService(AccountRepository accounts, LibraryRepository libraries, MembershipRepository memberships,
                          InvitationRepository invitations, Mailer mailer, ShelvedProperties props, JdbcTemplate jdbc,
                          CoverStore covers, PhotoStore photos) {
        this.accounts = accounts;
        this.libraries = libraries;
        this.memberships = memberships;
        this.invitations = invitations;
        this.mailer = mailer;
        this.props = props;
        this.jdbc = jdbc;
        this.covers = covers;
        this.photos = photos;
    }

    // ------------------------------------------------------------------ who and where

    /** SSO or dev login: the account behind the subject, created with its own library on first login. */
    @Transactional
    public UUID accountForSubject(String subject, String email, String name) {
        Optional<Account> existing = accounts.findByOidcSubject(subject);
        if (existing.isPresent()) {
            Account a = existing.get();
            if (a.getEmail() == null && email != null && accounts.findByEmail(normalize(email)).isEmpty()) {
                a.setEmail(normalize(email)); // migrated accounts learn their address at the next login
            }
            if (a.getDisplayName() == null && name != null) {
                a.setDisplayName(name);
            }
            return a.getId();
        }
        String mail = email == null || accounts.findByEmail(normalize(email)).isPresent() ? null : normalize(email);
        Account a = accounts.save(Account.fromSso(subject, mail, name));
        create(a.getId(), "Meine Bibliothek", Library.Kind.PRIVATE);
        return a.getId();
    }

    /** The library chosen in this session if still a member, else the oldest membership, else a new own one. */
    @Transactional
    public Membership currentMembership(UUID accountId, UUID preferred) {
        if (preferred != null) {
            Optional<Membership> m = memberships.findByLibraryIdAndAccountId(preferred, accountId);
            if (m.isPresent()) {
                return m.get();
            }
        }
        List<Membership> all = memberships.findByAccountIdOrderByCreatedAt(accountId);
        if (!all.isEmpty()) {
            return all.getFirst();
        }
        if (accounts.findById(accountId).isEmpty()) {
            return null;
        }
        return create(accountId, "Meine Bibliothek", Library.Kind.PRIVATE);
    }

    @Transactional
    public Membership create(UUID accountId, String name, Library.Kind kind) {
        Library l = libraries.save(new Library(cleanName(name), kind == null ? Library.Kind.PRIVATE : kind));
        return memberships.save(new Membership(l.getId(), accountId, Membership.Role.OWNER));
    }

    @Transactional(readOnly = true)
    public List<LibraryView> mine(CurrentUser user) {
        return memberships.findByAccountIdOrderByCreatedAt(user.accountId()).stream()
                .map(m -> {
                    Library l = libraries.findById(m.getLibraryId()).orElseThrow();
                    return new LibraryView(l.getId(), l.getName(), l.getKind(), m.getRole(), l.getId().equals(user.libraryId()));
                }).toList();
    }

    public Library library(UUID id) {
        return libraries.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden"));
    }

    @Transactional
    public Library update(CurrentUser user, String name, Library.Kind kind) {
        user.requireManager();
        Library l = library(user.libraryId());
        if (name != null) {
            l.setName(cleanName(name));
        }
        if (kind != null) {
            l.setKind(kind);
        }
        return l;
    }

    public void requireMember(UUID accountId, UUID libraryId) {
        memberships.findByLibraryIdAndAccountId(libraryId, accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden"));
    }

    // ------------------------------------------------------------------ members

    @Transactional(readOnly = true)
    public List<MemberView> members(CurrentUser user) {
        return memberships.findByLibraryIdOrderByCreatedAt(user.libraryId()).stream().map(m -> {
            Account a = accounts.findById(m.getAccountId()).orElseThrow();
            return new MemberView(a.getId(), a.getDisplayName(), a.getEmail(), m.getRole(), m.getCreatedAt());
        }).toList();
    }

    @Transactional
    public void changeRole(CurrentUser user, UUID accountId, Membership.Role role) {
        user.requireManager();
        Membership m = memberships.findByLibraryIdAndAccountId(user.libraryId(), accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mitglied nicht gefunden"));
        if (role == Membership.Role.OWNER && user.role() != Membership.Role.OWNER
                || m.getRole() == Membership.Role.OWNER && user.role() != Membership.Role.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur Eigentümer können Eigentümer ernennen oder ändern");
        }
        if (m.getRole() == Membership.Role.OWNER && role != Membership.Role.OWNER && owners(user.libraryId()) == 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Die Bibliothek braucht mindestens einen Eigentümer");
        }
        m.setRole(role);
    }

    @Transactional
    public void remove(CurrentUser user, UUID accountId) {
        if (!accountId.equals(user.accountId())) {
            user.requireManager();
        }
        Membership m = memberships.findByLibraryIdAndAccountId(user.libraryId(), accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mitglied nicht gefunden"));
        if (m.getRole() == Membership.Role.OWNER && owners(user.libraryId()) == 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Die Bibliothek braucht mindestens einen Eigentümer");
        }
        if (m.getRole() == Membership.Role.OWNER && user.role() != Membership.Role.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur Eigentümer können Eigentümer entfernen");
        }
        memberships.delete(m);
    }

    long owners(UUID libraryId) {
        return memberships.findByLibraryIdOrderByCreatedAt(libraryId).stream().filter(x -> x.getRole() == Membership.Role.OWNER).count();
    }

    // ------------------------------------------------------------------ invitations

    @Transactional
    public void invite(CurrentUser user, String email, Membership.Role role) {
        user.requireManager();
        if (role == Membership.Role.OWNER && user.role() != Membership.Role.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur Eigentümer können Eigentümer einladen");
        }
        String mail = normalize(email);
        Library l = library(user.libraryId());
        Account inviter = accounts.findById(user.accountId()).orElseThrow();
        String token = Tokens.create();
        invitations.save(new Invitation(l.getId(), mail, role, Tokens.hash(token), inviter.getId(), INVITATION_VALID));
        String who = inviter.getDisplayName() != null ? inviter.getDisplayName() : "Jemand";
        mailer.send(Mailer.Mail.of(mail, "Einladung in die Bibliothek „" + l.getName() + "“", """
                Hallo,

                %s lädt dich in die Bibliothek „%s“ bei shelved ein (Rolle: %s).

                Einladung annehmen:
                %s/#/invite?token=%s

                Der Link gilt 14 Tage. Wenn du damit nichts anfangen kannst, ignoriere diese Mail einfach.
                """.formatted(who, l.getName(), roleName(role), props.publicUrl(), token)));
    }

    @Transactional(readOnly = true)
    public List<InvitationView> openInvitations(CurrentUser user) {
        user.requireManager();
        return invitations.findByLibraryIdAndAcceptedAtIsNull(user.libraryId()).stream().filter(Invitation::open)
                .map(i -> new InvitationView(i.getId(), i.getEmail(), i.getRole(), i.getExpiresAt())).toList();
    }

    @Transactional
    public void withdraw(CurrentUser user, UUID invitationId) {
        user.requireManager();
        invitations.findById(invitationId).filter(i -> i.getLibraryId().equals(user.libraryId())).ifPresent(invitations::delete);
    }

    @Transactional(readOnly = true)
    public InvitationInfo invitation(String token) {
        Invitation i = openInvitation(token);
        String inviter = i.getInvitedBy() == null ? null : accounts.findById(i.getInvitedBy()).map(Account::getDisplayName).orElse(null);
        return new InvitationInfo(library(i.getLibraryId()).getName(), inviter, i.getEmail(), i.getRole());
    }

    Invitation openInvitation(String token) {
        return invitations.findByTokenHash(Tokens.hash(token == null ? "" : token)).filter(Invitation::open)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Die Einladung ist ungültig oder abgelaufen"));
    }

    /** Joins the library. Also used right after registering through the invitation. */
    @Transactional
    public UUID accept(String token, UUID accountId) {
        Invitation i = openInvitation(token);
        if (memberships.findByLibraryIdAndAccountId(i.getLibraryId(), accountId).isEmpty()) {
            memberships.save(new Membership(i.getLibraryId(), accountId, i.getRole()));
        }
        i.accept();
        return i.getLibraryId();
    }

    // ------------------------------------------------------------------ deleting

    /**
     * Before an account goes: libraries it alone owns but others use would be orphaned, so that is refused;
     * libraries where it is the only member are deleted with everything in them.
     */
    @Transactional
    public void leaveAll(UUID accountId) {
        List<Membership> mine = memberships.findByAccountIdOrderByCreatedAt(accountId);
        for (Membership m : mine) {
            List<Membership> all = memberships.findByLibraryIdOrderByCreatedAt(m.getLibraryId());
            boolean others = all.size() > 1;
            if (m.getRole() == Membership.Role.OWNER && others && owners(m.getLibraryId()) == 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Du bist der einzige Eigentümer von „"
                        + library(m.getLibraryId()).getName() + "“. Ernenne erst jemand anderen zum Eigentümer.");
            }
        }
        for (Membership m : mine) {
            if (memberships.findByLibraryIdOrderByCreatedAt(m.getLibraryId()).size() == 1) {
                deleteLibrary(m.getLibraryId());
            } else {
                memberships.delete(m);
            }
        }
    }

    void deleteLibrary(UUID libraryId) {
        String owner = libraryId.toString();
        jdbc.queryForList("select cover_file from book where owner_id = ? and cover_file is not null", String.class, owner)
                .forEach(covers::delete);
        jdbc.queryForList("select back_cover_file from book where owner_id = ? and back_cover_file is not null", String.class, owner)
                .forEach(covers::delete);
        jdbc.queryForList("select cover_file from wish where owner_id = ? and cover_file is not null", String.class, owner)
                .forEach(covers::delete);
        jdbc.queryForList("select photo_file from scan where owner_id = ? and photo_file is not null", String.class, owner)
                .forEach(photos::delete);
        jdbc.update("delete from book where owner_id = ?", owner);
        jdbc.update("delete from shelf where owner_id = ?", owner);
        jdbc.update("delete from scan where owner_id = ?", owner);
        jdbc.update("delete from wish where owner_id = ?", owner);
        libraries.deleteById(libraryId); // memberships and invitations cascade
    }

    // ------------------------------------------------------------------ helpers

    public static String normalize(String email) {
        if (email == null || !email.trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || email.length() > 254) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungültige E-Mail-Adresse");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    static String cleanName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Der Name der Bibliothek fehlt oder ist zu lang");
        }
        return n;
    }

    static String roleName(Membership.Role role) {
        return switch (role) {
            case OWNER -> "Eigentümer";
            case ADMIN -> "Leitung";
            case STAFF -> "Mitarbeit";
            case VIEWER -> "Nur lesen";
        };
    }
}
