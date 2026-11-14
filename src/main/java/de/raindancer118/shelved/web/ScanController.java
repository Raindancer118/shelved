package de.raindancer118.shelved.web;

import de.raindancer118.shelved.scan.Scan;
import de.raindancer118.shelved.scan.ScanItem;
import de.raindancer118.shelved.scan.ScanService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ScanController {

    public record ScanView(UUID id, Scan.Mode mode, Scan.Status status, UUID shelfId, Integer boardNumber,
                           String error, List<ScanItem> items) {
    }

    public record AcceptRequest(List<ScanService.Decision> items) {
    }

    private final ScanService scans;

    public ScanController(ScanService scans) {
        this.scans = scans;
    }

    @PostMapping(value = "/scans", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ScanView submit(Authentication auth, @RequestParam("image") MultipartFile image,
                           @RequestParam(defaultValue = "SHELF") Scan.Mode mode,
                           @RequestParam(required = false) UUID shelfId,
                           @RequestParam(required = false) Integer board) throws IOException {
        Scan s = scans.submit(Owner.of(auth), mode, image.getBytes(), shelfId, board);
        return view(s);
    }

    /** Fallback for browsers without live barcode scanning: decode a photo of the back cover locally. */
    @PostMapping(value = "/barcode", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public java.util.Map<String, String> barcode(@RequestParam("image") MultipartFile image) throws IOException {
        String isbn = de.raindancer118.shelved.scan.BarcodeReader.isbn(ScanService.decode(image.getBytes()))
                .orElseThrow(() -> new de.raindancer118.shelved.book.NotFoundException("Barcode"));
        return java.util.Map.of("isbn", isbn);
    }

    @GetMapping("/scans/{id}")
    public ScanView get(Authentication auth, @PathVariable UUID id) {
        return view(scans.get(Owner.of(auth), id));
    }

    @PostMapping("/scans/{id}/accept")
    public ScanService.AcceptResult accept(Authentication auth, @PathVariable UUID id, @RequestBody AcceptRequest req) {
        return scans.accept(Owner.of(auth), id, req.items() == null ? List.of() : req.items());
    }

    private ScanView view(Scan s) {
        return new ScanView(s.getId(), s.getMode(), s.getStatus(), s.getShelfId(), s.getBoardNumber(), s.getError(),
                scans.result(s).items());
    }
}
