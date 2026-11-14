package de.raindancer118.shelved.inventory;

import de.raindancer118.shelved.book.CoverStore;
import de.raindancer118.shelved.book.NotFoundException;
import de.raindancer118.shelved.scan.ScanService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class InventoryController {

    public record PhotoView(UUID id, String url, String label, Instant takenAt) {
    }

    private final InventoryService inventory;
    private final ScanService scans;
    private final CoverStore covers;

    public InventoryController(InventoryService inventory, ScanService scans, CoverStore covers) {
        this.inventory = inventory;
        this.scans = scans;
        this.covers = covers;
    }

    @GetMapping("/photos")
    public List<PhotoView> photos(Authentication auth) {
        return inventory.build(owner(auth), null).photos().stream()
                .map(p -> new PhotoView(p.scanId(), "/api/photos/" + p.scanId(), p.label(), p.takenAt())).toList();
    }

    @GetMapping("/photos/{id}")
    public ResponseEntity<FileSystemResource> photo(Authentication auth, @PathVariable UUID id) {
        var path = scans.photo(owner(auth), id).orElseThrow(() -> new NotFoundException("Foto"));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400").body(new FileSystemResource(path));
    }

    @GetMapping("/export/inventory.pdf")
    public ResponseEntity<byte[]> pdf(Authentication auth) {
        String owner = owner(auth);
        InventoryService.Inventory inv = inventory.build(owner, displayName(auth));
        byte[] pdf = InventoryPdf.render(inv, b -> covers.path(b.getCoverFile()), id -> scans.photo(owner, id));
        return download(pdf, MediaType.APPLICATION_PDF, "shelved-inventar-" + inv.date() + ".pdf");
    }

    @GetMapping("/export/inventory.csv")
    public ResponseEntity<byte[]> csv(Authentication auth) {
        InventoryService.Inventory inv = inventory.build(owner(auth), null);
        return download(InventoryCsv.render(inv), new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8),
                "shelved-inventar-" + inv.date() + ".csv");
    }

    private static ResponseEntity<byte[]> download(byte[] body, MediaType type, String name) {
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private static String owner(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("Nicht angemeldet");
        }
        return auth.getName();
    }

    private static String displayName(Authentication auth) {
        if (auth.getPrincipal() instanceof OidcUser u) {
            return u.getFullName() != null ? u.getFullName() : u.getPreferredUsername();
        }
        return null;
    }
}
