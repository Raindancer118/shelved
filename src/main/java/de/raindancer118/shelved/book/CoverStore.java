package de.raindancer118.shelved.book;

import de.raindancer118.shelved.config.ShelvedProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Covers are copied to our own disk so the browser never talks to third parties (and they survive link rot). */
@Component
public class CoverStore {

    private static final Logger log = LoggerFactory.getLogger(CoverStore.class);
    private static final int MIN_BYTES = 2_000; // placeholder pixels and "no cover" GIFs are tiny

    private final Path dir;
    private final Path catalogDir;
    private final RestClient http;
    /** ISBNs without any cover anywhere, so a list of search hits doesn't ask the same sources again and again. */
    private final java.util.Map<String, java.time.Instant> misses = new java.util.concurrent.ConcurrentHashMap<>();

    public CoverStore(ShelvedProperties props, RestClient externalHttp) {
        this.dir = props.dataDir().resolve("covers");
        this.catalogDir = dir.resolve("catalog");
        this.http = externalHttp;
        try {
            Files.createDirectories(catalogDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create cover directory " + dir, e);
        }
    }

    public static List<String> candidates(String isbn13, String metadataCoverUrl) {
        return java.util.stream.Stream.of(
                        metadataCoverUrl,
                        isbn13 == null ? null : "https://portal.dnb.de/opac/mvb/cover?isbn=" + isbn13,
                        isbn13 == null ? null : "https://covers.openlibrary.org/b/isbn/" + isbn13 + "-L.jpg?default=false")
                .filter(Objects::nonNull).distinct().toList();
    }

    private record Image(byte[] bytes, String ext) {
    }

    /** Tries the URLs in order and stores the first real image. Returns the file name. */
    public Optional<String> fetch(UUID bookId, List<String> urls) {
        return download(urls).map(img -> save(bookId, img.bytes(), img.ext()));
    }

    private Optional<Image> download(List<String> urls) {
        for (String url : urls) {
            try {
                ResponseEntity<byte[]> r = http.get().uri(URI.create(url)).retrieve().toEntity(byte[].class);
                byte[] body = r.getBody();
                var type = r.getHeaders().getContentType();
                if (body != null && body.length >= MIN_BYTES && type != null && "image".equals(type.getType())) {
                    return Optional.of(new Image(body, type.getSubtype().contains("png") ? "png" : "jpg"));
                }
            } catch (RestClientException e) {
                log.debug("No cover at {}: {}", url, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * Cover of any edition by ISBN, for books that are not (yet) ours: search hits, scans, wishes. Fetched once from
     * DNB or Open Library, then served from disk. A miss is remembered for a day.
     */
    public Optional<Path> catalogCover(String isbn13) {
        if (isbn13 == null || !isbn13.matches("97[89]\\d{10}")) {
            return Optional.empty();
        }
        for (String ext : List.of("jpg", "png")) {
            Path p = catalogDir.resolve(isbn13 + "." + ext);
            if (Files.isRegularFile(p)) {
                return Optional.of(p);
            }
        }
        java.time.Instant missed = misses.get(isbn13);
        if (missed != null && missed.isAfter(java.time.Instant.now().minus(java.time.Duration.ofDays(1)))) {
            return Optional.empty();
        }
        Optional<Image> img = download(candidates(isbn13, null));
        if (img.isEmpty()) {
            misses.put(isbn13, java.time.Instant.now());
            return Optional.empty();
        }
        try {
            Path p = catalogDir.resolve(isbn13 + "." + img.get().ext());
            Files.write(p, img.get().bytes());
            return Optional.of(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String save(UUID bookId, byte[] image, String ext) {
        String name = bookId + "-" + Long.toString(System.currentTimeMillis(), 36) + "." + ext;
        try {
            Files.write(dir.resolve(name), image);
            return name;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<Path> path(String file) {
        if (file == null || file.contains("/") || file.contains("..")) {
            return Optional.empty();
        }
        Path p = dir.resolve(file);
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    public void delete(String file) {
        path(file).ifPresent(p -> {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.warn("Could not delete cover {}", p);
            }
        });
    }
}
