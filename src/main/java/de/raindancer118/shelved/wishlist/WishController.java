package de.raindancer118.shelved.wishlist;

import de.raindancer118.shelved.book.CoverStore;
import de.raindancer118.shelved.book.NotFoundException;
import de.raindancer118.shelved.web.Owner;
import de.raindancer118.shelved.web.CoverResponse;
import de.raindancer118.shelved.web.Views;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/wishes")
public class WishController {

    public record WishInput(@Size(max = 20) String isbn, @Size(max = 500) String title,
                            @Size(max = 10) List<@Size(max = 200) String> authors) {
    }

    public record Resolve(@Size(max = 500) List<UUID> remove, @Size(max = 500) List<UUID> keep) {
    }

    public record WishView(UUID id, String isbn13, String title, String subtitle, List<String> authors, String publisher,
                           Integer publishedYear, Integer listPriceCents,
                           String cover, UUID ownedBookId, String ownedCover, boolean keepWhenOwned, Instant createdAt) {
    }

    private final WishService wishes;
    private final CoverStore covers;

    public WishController(WishService wishes, CoverStore covers) {
        this.wishes = wishes;
        this.covers = covers;
    }

    static WishView view(Wish w, de.raindancer118.shelved.book.Book owned) {
        String cover = w.getCoverFile() == null ? null : "/api/wishes/" + w.getId() + "/cover?v=f" + w.getCoverFile().hashCode(); // f: served as the cut front since 0.9.1
        return new WishView(w.getId(), w.getIsbn13(), w.getTitle(), w.getSubtitle(), w.getAuthors(), w.getPublisher(),
                w.getPublishedYear(), w.getListPriceCents(), cover, owned == null ? null : owned.getId(),
                owned == null ? null : Views.cover(owned),
                w.isKeepWhenOwned(), w.getCreatedAt());
    }

    private static List<WishView> views(List<WishService.Entry> entries) {
        return entries.stream().map(e -> view(e.wish(), e.owned())).toList();
    }

    @GetMapping
    public List<WishView> list(Authentication auth) {
        return views(wishes.all(Owner.of(auth)));
    }

    @GetMapping("/fulfilled")
    public List<WishView> fulfilled(Authentication auth) {
        return views(wishes.fulfilled(Owner.of(auth)));
    }

    @PostMapping
    public ResponseEntity<WishView> add(Authentication auth, @Valid @RequestBody WishInput in) {
        var a = wishes.add(Owner.of(auth), in.isbn(), in.title(), in.authors());
        return ResponseEntity.status(a.created() ? HttpStatus.CREATED : HttpStatus.OK).body(view(a.wish(), null));
    }

    @GetMapping("/{id}")
    public WishView get(Authentication auth, @PathVariable UUID id) {
        var e = wishes.entry(Owner.of(auth), id);
        return view(e.wish(), e.owned());
    }

    @PostMapping("/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolve(Authentication auth, @Valid @RequestBody Resolve in) {
        wishes.resolve(Owner.of(auth), in.remove(), in.keep());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable UUID id) {
        wishes.delete(Owner.of(auth), id);
    }

    @GetMapping("/{id}/cover")
    public ResponseEntity<FileSystemResource> cover(Authentication auth, @PathVariable UUID id) {
        Wish w = wishes.get(Owner.of(auth), id);
        var path = covers.path(w.getCoverFile()).map(covers::flat).orElseThrow(() -> new NotFoundException("Cover"));
        return CoverResponse.of(path, true);
    }
}
