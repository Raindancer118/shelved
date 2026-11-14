package de.raindancer118.shelved.library;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Libraries of the current person, the members of the current library and invitations. */
@RestController
public class LibraryAdminController {

    public record NewLibrary(String name, Library.Kind kind) {
    }

    public record LibraryUpdate(String name, Library.Kind kind) {
    }

    public record RoleChange(Membership.Role role) {
    }

    public record Invite(String email, Membership.Role role) {
    }

    private final LibraryService libraries;

    public LibraryAdminController(LibraryService libraries) {
        this.libraries = libraries;
    }

    @GetMapping("/api/libraries")
    public List<LibraryService.LibraryView> mine() {
        return libraries.mine(CurrentUser.get());
    }

    @PostMapping("/api/libraries")
    @ResponseStatus(HttpStatus.CREATED)
    public LibraryService.LibraryView create(@RequestBody NewLibrary n, HttpServletRequest request) {
        Membership m = libraries.create(CurrentUser.get().accountId(), n.name(), n.kind());
        request.getSession().setAttribute(TenantFilter.SESSION_LIBRARY, m.getLibraryId());
        Library l = libraries.library(m.getLibraryId());
        return new LibraryService.LibraryView(l.getId(), l.getName(), l.getKind(), m.getRole(), true);
    }

    @PostMapping("/api/libraries/{id}/select")
    public void select(@PathVariable UUID id, HttpServletRequest request) {
        libraries.requireMember(CurrentUser.get().accountId(), id);
        request.getSession().setAttribute(TenantFilter.SESSION_LIBRARY, id);
    }

    @PatchMapping("/api/library")
    public LibraryService.LibraryView update(@RequestBody LibraryUpdate u) {
        CurrentUser user = CurrentUser.get();
        Library l = libraries.update(user, u.name(), u.kind());
        return new LibraryService.LibraryView(l.getId(), l.getName(), l.getKind(), user.role(), true);
    }

    @GetMapping("/api/library/members")
    public List<LibraryService.MemberView> members() {
        return libraries.members(CurrentUser.get());
    }

    @PatchMapping("/api/library/members/{accountId}")
    public void role(@PathVariable UUID accountId, @RequestBody RoleChange r) {
        libraries.changeRole(CurrentUser.get(), accountId, r.role());
    }

    @DeleteMapping("/api/library/members/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID accountId) {
        libraries.remove(CurrentUser.get(), accountId);
    }

    @GetMapping("/api/library/invitations")
    public List<LibraryService.InvitationView> invitations() {
        return libraries.openInvitations(CurrentUser.get());
    }

    @PostMapping("/api/library/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public void invite(@RequestBody Invite i) {
        libraries.invite(CurrentUser.get(), i.email(), i.role() == null ? Membership.Role.STAFF : i.role());
    }

    @DeleteMapping("/api/library/invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@PathVariable UUID id) {
        libraries.withdraw(CurrentUser.get(), id);
    }

    /** Public: what the invitation link is about, before logging in or registering. */
    @GetMapping("/api/invitations/{token}")
    public LibraryService.InvitationInfo invitation(@PathVariable String token) {
        return libraries.invitation(token);
    }

    @PostMapping("/api/invitations/{token}/accept")
    public void accept(@PathVariable String token, HttpServletRequest request) {
        UUID library = libraries.accept(token, CurrentUser.get().accountId());
        request.getSession().setAttribute(TenantFilter.SESSION_LIBRARY, library);
    }
}
