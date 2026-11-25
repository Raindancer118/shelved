package de.raindancer118.shelved.wishlist;

import de.raindancer118.shelved.book.CoverStore;
import de.raindancer118.shelved.book.NotFoundException;
import de.raindancer118.shelved.web.Owner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/wishes")
public class WishController {

    public record WishInput(@Size(max = 20) String isbn, @Size(max = 500) String title,
                            @Size(max = 10) List<@Size(max = 200) String> authors,
                            @Size(max = 300) String seriesName, @Min(0) @Max(10000) Integer seriesVolume) {
    }

    public record Resolve(@Size(max = 500) List<UUID> remove, @Size(max = 500) List<UUID> keep) {
    }

    public record WishView(UUID id, String isbn13, String title, String subtitle, List<String> authors, String publisher,
                           Integer publishedYear, String seriesName, Integer seriesVolume, Integer listPriceCents,
                           String cover, UUID ownedBookId, boolean keepWhenOwned, Instant createdAt) {
    }

    private final WishService wishes;
    private final CoverStore covers;

    public WishController(WishService wishes, CoverStore covers) {
        this.wishes = wishes;
        this.covers = covers;
    }

    static WishView view(Wish w, UUID ownedBookId) {
        String cover = w.getCoverFile() == null ? null : "/api/wishes/" + w.getId() + "/cover?v=" + w.getCoverFile().hashCode();
        return new WishView(w.getId(), w.getIsbn13(), w.getTitle(), w.getSubtitle(), w.getAuthors(), w.getPublisher(),
                w.getPublishedYear(), w.getSeriesName(), w.getSeriesVolume(), w.getListPriceCents(), cover, ownedBookId,
                w.isKeepWhenOwned(), w.getCreatedAt());
    }

    private static List<WishView> views(List<WishService.Entry> entries) {
        return entries.stream().map(e -> view(e.wish(), e.owned() == null ? null : e.owned().getId())).toList();
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
        var a = wishes.add(Owner.of(auth), in.isbn(), in.title(), in.authors(), in.seriesName(), in.seriesVolume());
        return ResponseEntity.status(a.created() ? HttpStatus.CREATED : HttpStatus.OK).body(view(a.wish(), null));
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
        var path = covers.path(w.getCoverFile()).orElseThrow(() -> new NotFoundException("Cover"));
        MediaType type = path.toString().endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .body(new FileSystemResource(path));
    }
}
