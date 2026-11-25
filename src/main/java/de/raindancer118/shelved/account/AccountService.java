package de.raindancer118.shelved.account;

import de.raindancer118.shelved.config.ShelvedProperties;
import de.raindancer118.shelved.library.Library;
import de.raindancer118.shelved.library.LibraryService;
import de.raindancer118.shelved.library.Membership;
import de.raindancer118.shelved.library.MembershipRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Registration, confirmation, password login and reset, and everything a person may do with their account. */
@Service
public class AccountService {

    static final Duration VERIFY_VALID = Duration.ofDays(2);
    static final Duration RESET_VALID = Duration.ofHours(1);
    static final int MIN_PASSWORD = 10;

    private final AccountRepository accounts;
    private final AccountTokenRepository tokens;
    private final MembershipRepository memberships;
    private final LibraryService libraries;
    private final Mailer mailer;
    private final ShelvedProperties props;
    private final JsonMapper json;
    private final LoginThrottle throttle;
    private final DataExport export;
    private final de.raindancer118.shelved.book.CoverStore covers;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(12);
    // same cost for unknown addresses, so timing doesn't tell which accounts exist
    private final String dummyHash = encoder.encode("unknown-account-dummy");

    public AccountService(AccountRepository accounts, AccountTokenRepository tokens, MembershipRepository memberships,
                          LibraryService libraries, Mailer mailer, ShelvedProperties props, JsonMapper json, LoginThrottle throttle,
                          DataExport export, de.raindancer118.shelved.book.CoverStore covers) {
        this.export = export;
        this.covers = covers;
        this.accounts = accounts;
        this.tokens = tokens;
        this.memberships = memberships;
        this.libraries = libraries;
        this.mailer = mailer;
        this.props = props;
        this.json = json;
        this.throttle = throttle;
    }

    // ------------------------------------------------------------------ registration

    /**
     * New account with its own library, or (with an invitation) a member of the inviting library. The answer is the
     * same whether the address is taken or not; the owner of a taken address gets a hint by mail instead.
     */
    @Transactional
    public void register(String email, String name, String password, String libraryName, Library.Kind kind, String inviteToken) {
        String mail = LibraryService.normalize(email);
        checkPassword(password);
        String displayName = cleanDisplayName(name);
        if (accounts.findByEmail(mail).isPresent()) {
            mailer.send(Mailer.Mail.of(mail, "Für diese Adresse gibt es bereits ein Konto", """
                    Hallo,

                    gerade wollte jemand mit dieser Adresse ein neues Konto bei shelved anlegen. Es gibt aber schon eins.
                    Passwort vergessen? Hier setzt du es neu: %s/#/forgot

                    Warst du das nicht, kannst du diese Mail ignorieren.
                    """.formatted(props.publicUrl())));
            return;
        }
        Account a = accounts.save(Account.withPassword(mail, displayName, encoder.encode(password)));
        if (inviteToken != null && !inviteToken.isBlank()) {
            var info = libraries.invitation(inviteToken);
            if (!info.email().equalsIgnoreCase(mail)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Die Einladung gilt für eine andere Adresse");
            }
            a.verified(); // the link reached this mailbox
            libraries.accept(inviteToken, a.getId());
            return;
        }
        libraries.create(a.getId(), libraryName == null || libraryName.isBlank() ? "Meine Bibliothek" : libraryName, kind);
        sendLink(a, AccountToken.Kind.VERIFY, mail, null, VERIFY_VALID, "Bitte bestätige deine E-Mail-Adresse", """
                Hallo %s,

                willkommen bei shelved. Bitte bestätige deine Adresse, dann kannst du dich anmelden:
                %s

                Der Link gilt zwei Tage.
                """);
    }

    /** Confirms a new account or a changed address. */
    @Transactional
    public void verify(String token) {
        AccountToken t = usable(token, AccountToken.Kind.VERIFY, AccountToken.Kind.CHANGE_EMAIL);
        Account a = accounts.findById(t.getAccountId()).orElseThrow();
        if (t.getKind() == AccountToken.Kind.CHANGE_EMAIL) {
            if (accounts.findByEmail(t.getPayload()).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Diese Adresse gehört inzwischen zu einem anderen Konto");
            }
            a.setEmail(t.getPayload());
        }
        a.verified();
        t.use();
    }

    // ------------------------------------------------------------------ login and password

    @Transactional(readOnly = true)
    public UUID login(String email, String password, String ip) {
        String mail = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
        throttle.check(mail, ip);
        Account a = accounts.findByEmail(mail).orElse(null);
        boolean ok = encoder.matches(password == null ? "" : password, a == null || a.getPasswordHash() == null ? dummyHash : a.getPasswordHash())
                && a != null && a.getPasswordHash() != null;
        if (!ok) {
            throttle.failed(mail, ip);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-Mail oder Passwort stimmen nicht");
        }
        if (!a.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bitte bestätige zuerst deine E-Mail-Adresse (Link in der Mail)");
        }
        throttle.succeeded(mail, ip);
        return a.getId();
    }

    @Transactional
    public void forgot(String email, String ip) {
        throttle.checkMail(ip);
        String mail;
        try {
            mail = LibraryService.normalize(email);
        } catch (ResponseStatusException e) {
            return;
        }
        accounts.findByEmail(mail).ifPresent(a -> sendLink(a, AccountToken.Kind.RESET, mail, null, RESET_VALID,
                "Neues Passwort für shelved", """
                        Hallo %s,

                        hier kannst du ein neues Passwort setzen:
                        %s

                        Der Link gilt eine Stunde und funktioniert einmal. Hast du das nicht angefordert, ignoriere diese Mail.
                        """));
    }

    @Transactional
    public void reset(String token, String password) {
        checkPassword(password);
        AccountToken t = usable(token, AccountToken.Kind.RESET);
        Account a = accounts.findById(t.getAccountId()).orElseThrow();
        a.setPasswordHash(encoder.encode(password));
        a.verified(); // the reset link reached the mailbox
        t.use();
    }

    @Transactional
    public void changePassword(UUID accountId, String current, String password) {
        Account a = accounts.findById(accountId).orElseThrow();
        if (a.getPasswordHash() != null && !encoder.matches(current == null ? "" : current, a.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Das aktuelle Passwort stimmt nicht");
        }
        checkPassword(password);
        a.setPasswordHash(encoder.encode(password));
    }

    // ------------------------------------------------------------------ profile

    @Transactional(readOnly = true)
    public Account get(UUID accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    @Transactional
    public void rename(UUID accountId, String name) {
        get(accountId).setDisplayName(cleanDisplayName(name));
    }

    /** The new address only counts once its link was clicked. */
    @Transactional
    public void changeEmail(UUID accountId, String email, String password) {
        Account a = get(accountId);
        requirePassword(a, password);
        String mail = LibraryService.normalize(email);
        if (accounts.findByEmail(mail).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Diese Adresse gehört schon zu einem Konto");
        }
        sendLink(a, AccountToken.Kind.CHANGE_EMAIL, mail, mail, VERIFY_VALID, "Neue E-Mail-Adresse bestätigen", """
                Hallo %s,

                bitte bestätige deine neue Adresse für shelved:
                %s
                """);
    }

    @Transactional
    public void delete(UUID accountId, String password) {
        Account a = get(accountId);
        requirePassword(a, password);
        libraries.leaveAll(accountId);
        accounts.delete(a);
    }

    /** Everything stored about the person, as JSON, sent to the address on file. */
    @Transactional(readOnly = true)
    public void export(UUID accountId) {
        Account a = get(accountId);
        if (a.getEmail() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Zu deinem Konto ist keine E-Mail-Adresse hinterlegt");
        }
        DataExport.Export data = export.collect(a);
        byte[] pdf = ExportPdf.render(data, file -> covers.path(file).map(covers::flat));
        byte[] machine = json.writerWithDefaultPrettyPrinter().writeValueAsString(data).getBytes(StandardCharsets.UTF_8);
        mailer.send(new Mailer.Mail(a.getEmail(), "Deine Daten bei shelved", """
                Hallo %s,

                im Anhang findest du alles, was shelved über dich speichert (Art. 15 DSGVO):

                shelved-daten.pdf   zum Lesen und Ausdrucken, mit allen Büchern, deiner Wunschliste und deinen Regalen
                shelved-daten.json  dieselben Daten maschinenlesbar, zum Mitnehmen in eine andere App
                """.formatted(greeting(a)), List.of(
                new Mailer.Attachment("shelved-daten.pdf", "application/pdf", pdf),
                new Mailer.Attachment("shelved-daten.json", "application/json", machine))));
    }

    // ------------------------------------------------------------------ helpers

    private void sendLink(Account a, AccountToken.Kind kind, String to, String payload, Duration valid, String subject, String text) {
        String token = Tokens.create();
        tokens.save(new AccountToken(a.getId(), kind, Tokens.hash(token), payload, valid));
        String page = kind == AccountToken.Kind.RESET ? "reset" : "verify";
        mailer.send(Mailer.Mail.of(to, subject, text.formatted(greeting(a), props.publicUrl() + "/#/" + page + "?token=" + token)));
    }

    private AccountToken usable(String token, AccountToken.Kind... kinds) {
        AccountToken t = tokens.findByTokenHash(Tokens.hash(token == null ? "" : token))
                .filter(AccountToken::usable)
                .filter(x -> List.of(kinds).contains(x.getKind()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Der Link ist ungültig, abgelaufen oder wurde schon benutzt"));
        return t;
    }

    private void requirePassword(Account a, String password) {
        // accounts that only log in through the portal have no password; the portal login is the proof
        if (a.getPasswordHash() != null && !encoder.matches(password == null ? "" : password, a.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Das Passwort stimmt nicht");
        }
    }

    static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD || password.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Das Passwort braucht mindestens " + MIN_PASSWORD + " Zeichen");
        }
    }

    static String cleanDisplayName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte gib einen Namen an (höchstens 120 Zeichen)");
        }
        return n;
    }

    private static String greeting(Account a) {
        return a.getDisplayName() == null ? "" : a.getDisplayName();
    }
}
