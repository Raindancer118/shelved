package de.raindancer118.shelved.account;

import de.raindancer118.shelved.library.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** The logged-in person's own account (DSGVO: change, delete, get a copy of the data). */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    public record AccountView(String name, String email, boolean hasPassword, boolean sso, Instant createdAt) {
    }

    public record Name(String name) {
    }

    public record PasswordChange(String current, String password) {
    }

    public record EmailChange(String email, String password) {
    }

    public record Confirm(String password) {
    }

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public AccountView get() {
        Account a = accounts.get(CurrentUser.get().accountId());
        return new AccountView(a.getDisplayName(), a.getEmail(), a.getPasswordHash() != null, a.getOidcSubject() != null, a.getCreatedAt());
    }

    @PatchMapping
    public AccountView rename(@RequestBody Name n) {
        accounts.rename(CurrentUser.get().accountId(), n.name());
        return get();
    }

    @PostMapping("/password")
    public void password(@RequestBody PasswordChange p) {
        accounts.changePassword(CurrentUser.get().accountId(), p.current(), p.password());
    }

    @PostMapping("/email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void email(@RequestBody EmailChange e) {
        accounts.changeEmail(CurrentUser.get().accountId(), e.email(), e.password());
    }

    @PostMapping("/export")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void export() {
        accounts.export(CurrentUser.get().accountId());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestBody(required = false) Confirm c, HttpServletRequest request) {
        accounts.delete(CurrentUser.get().accountId(), c == null ? null : c.password());
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
