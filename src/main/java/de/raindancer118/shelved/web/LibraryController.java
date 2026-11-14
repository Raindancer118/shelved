package de.raindancer118.shelved.web;

import de.raindancer118.shelved.ai.LibraryAi;
import de.raindancer118.shelved.placement.PlacementService;
import de.raindancer118.shelved.series.SeriesService;
import de.raindancer118.shelved.shelf.ShelfRepository;
import de.raindancer118.shelved.stats.StatsService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class LibraryController {

    public record CurrentLibrary(UUID id, String name, de.raindancer118.shelved.library.Library.Kind kind,
                                 de.raindancer118.shelved.library.Membership.Role role) {
    }

    public record Me(String name, String email, boolean aiAvailable, CurrentLibrary library) {
    }

    public record VolumeView(int number, String title, Views.BookSummary book) {
    }

    public record SeriesView(String key, String name, String author, int ownedCount, int missingCount, Integer knownTotal,
                             String catalogStatus, String catalogSource, String catalogNote, List<String> covers, List<VolumeView> volumes) {
    }

    public record PlacedView(Views.BookSummary book, int position, int thicknessMm, PlacementService.Status status, String from) {
    }

    public record BoardView(UUID shelfId, String shelfName, int shelfNumber, int board, int widthMm, int usedMm, List<PlacedView> books) {
    }

    public record PlanView(List<BoardView> boards, List<Views.BookSummary> overflow, int toMove, int toPlace) {
    }

    private final SeriesService series;
    private final PlacementService placement;
    private final StatsService stats;
    private final ShelfRepository shelves;
    private final LibraryAi ai;

    private final de.raindancer118.shelved.account.AccountService accounts;
    private final de.raindancer118.shelved.library.LibraryService libraries;

    public LibraryController(SeriesService series, PlacementService placement, StatsService stats, ShelfRepository shelves, LibraryAi ai,
                             de.raindancer118.shelved.account.AccountService accounts, de.raindancer118.shelved.library.LibraryService libraries) {
        this.accounts = accounts;
        this.libraries = libraries;
        this.series = series;
        this.placement = placement;
        this.stats = stats;
        this.shelves = shelves;
        this.ai = ai;
    }

    /** Touching the token makes Spring Security write the XSRF-TOKEN cookie the frontend echoes back. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(org.springframework.security.web.csrf.CsrfToken token) {
        return Map.of("header", token.getHeaderName());
    }

    @GetMapping("/me")
    public Me me(Authentication auth) {
        var user = de.raindancer118.shelved.library.CurrentUser.get();
        var account = accounts.get(user.accountId());
        var library = libraries.library(user.libraryId());
        String name = account.getDisplayName() != null ? account.getDisplayName()
                : auth.getPrincipal() instanceof OidcUser u && u.getGivenName() != null ? u.getGivenName() : account.getEmail();
        return new Me(name, account.getEmail(), ai.available(),
                new CurrentLibrary(library.getId(), library.getName(), library.getKind(), user.role()));
    }

    private Map<UUID, Integer> numbers(String owner) {
        var list = shelves.findByOwnerIdOrderByPosition(owner);
        return java.util.stream.IntStream.range(0, list.size()).boxed()
                .collect(Collectors.toMap(i -> list.get(i).getId(), i -> i + 1));
    }

    @GetMapping("/series")
    public List<SeriesView> series(Authentication auth) {
        String owner = Owner.of(auth);
        var n = numbers(owner);
        return series.overview(owner).stream().map(v -> view(v, n, false)).toList();
    }

    @GetMapping("/series/{key}")
    public SeriesView seriesDetail(Authentication auth, @PathVariable String key) {
        String owner = Owner.of(auth);
        return view(series.detail(owner, key), numbers(owner), true);
    }

    @PostMapping("/series/recheck")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Integer> recheck(Authentication auth) {
        return Map.of("books", series.recheck(Owner.of(auth)));
    }

    @PostMapping("/series/{key}/catalog")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SeriesView refreshCatalog(Authentication auth, @PathVariable String key) {
        String owner = Owner.of(auth);
        return view(series.refresh(owner, key), numbers(owner), true);
    }

    private static SeriesView view(SeriesService.SeriesView v, Map<UUID, Integer> n, boolean withVolumes) {
        List<String> covers = v.owned().stream().map(Views::cover).filter(c -> c != null).limit(4).toList();
        List<VolumeView> vols = withVolumes
                ? v.volumes().stream().map(x -> new VolumeView(x.number(), x.title(), x.book() == null ? null : Views.summary(x.book(), n))).toList()
                : List.of();
        return new SeriesView(v.key(), v.name(), v.author(), v.owned().size(), v.missingCount(), v.knownTotal(),
                v.catalogStatus(), v.catalogSource(), v.catalogNote(), covers, vols);
    }

    @GetMapping("/placement")
    public PlanView placement(Authentication auth) {
        String owner = Owner.of(auth);
        var n = numbers(owner);
        var plan = placement.plan(owner);
        return new PlanView(
                plan.boards().stream().map(b -> new BoardView(b.shelfId(), b.shelfName(), b.shelfNumber(), b.board(), b.widthMm(), b.usedMm(),
                        b.books().stream().map(p -> new PlacedView(Views.summary(p.book(), n), p.position(), p.thicknessMm(), p.status(), p.from())).toList())).toList(),
                plan.overflow().stream().map(b -> Views.summary(b, n)).toList(), plan.toMove(), plan.toPlace());
    }

    @GetMapping("/stats")
    public StatsService.Stats stats(Authentication auth) {
        return stats.stats(Owner.of(auth));
    }
}
