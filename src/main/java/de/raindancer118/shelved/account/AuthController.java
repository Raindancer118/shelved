package de.raindancer118.shelved.account;

import de.raindancer118.shelved.config.ShelvedProperties;
import de.raindancer118.shelved.library.AccountPrincipal;
import de.raindancer118.shelved.library.Library;
import de.raindancer118.shelved.library.TenantFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Public endpoints: everything before one is logged in. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record Register(String email, String name, String password, String libraryName, Library.Kind kind, String inviteToken) {
    }

    public record Login(String email, String password) {
    }

    public record TokenOnly(String token) {
    }

    public record Reset(String token, String password) {
    }

    public record Email(String email) {
    }

    private final AccountService accounts;
    private final LoginThrottle throttle;
    private final ShelvedProperties props;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public AuthController(AccountService accounts, LoginThrottle throttle, ShelvedProperties props) {
        this.accounts = accounts;
        this.throttle = throttle;
        this.props = props;
    }

    /** What the login page should offer. */
    @GetMapping("/options")
    public Map<String, Object> options() {
        return Map.of("sso", props.oidcAuth(), "ssoUrl", "/oauth2/authorization/authentik");
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@RequestBody Register r, HttpServletRequest request) {
        throttle.checkMail(request.getRemoteAddr());
        accounts.register(r.email(), r.name(), r.password(), r.libraryName(), r.kind(), r.inviteToken());
    }

    @PostMapping("/verify")
    public void verify(@RequestBody TokenOnly t) {
        accounts.verify(t.token());
    }

    @PostMapping("/login")
    public void login(@RequestBody Login l, HttpServletRequest request, HttpServletResponse response) {
        UUID id = accounts.login(l.email(), l.password(), request.getRemoteAddr());
        var auth = UsernamePasswordAuthenticationToken.authenticated(new AccountPrincipal(id), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        request.getSession(true);
        request.changeSessionId(); // no session fixation
        request.getSession().removeAttribute(TenantFilter.SESSION_LIBRARY);
        contexts.saveContext(context, request, response);
    }

    @PostMapping("/forgot")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgot(@RequestBody Email e, HttpServletRequest request) {
        accounts.forgot(e.email(), request.getRemoteAddr());
    }

    @PostMapping("/reset")
    public void reset(@RequestBody Reset r) {
        accounts.reset(r.token(), r.password());
    }
}
