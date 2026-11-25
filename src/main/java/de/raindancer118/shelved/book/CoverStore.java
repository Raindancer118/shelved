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
    private final Path flatDir;
    private final RestClient http;
    /** ISBNs without any cover anywhere, so a list of search hits doesn't ask the same sources again and again. */
    private final java.util.Map<String, java.time.Instant> misses = new java.util.concurrent.ConcurrentHashMap<>();

    public CoverStore(ShelvedProperties props, RestClient externalHttp) {
        this.dir = props.dataDir().resolve("covers");
        this.catalogDir = dir.resolve("catalog");
        this.flatDir = dir.resolve("flat");
        this.http = externalHttp;
        try {
            Files.createDirectories(catalogDir);
            Files.createDirectories(flatDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create cover directory " + dir, e);
        }
    }

    public static List<String> candidates(String isbn13, String metadataCoverUrl) {
        return java.util.stream.Stream.of(
                        metadataCoverUrl,
                        isbn13 == null ? null : "https://portal.dnb.de/opac/mvb/cover?isbn=" + isbn13,
                        isbn13 == null ? null : "https://covers.openlibrary.org/b/isbn/" + isbn13 + "-L.jpg?default=false",
                        // English editions are often only known to Google; for unknown ISBNs it sends a 1 KB placeholder (below MIN_BYTES)
                        isbn13 == null ? null : "https://books.google.com/books/content?printsec=frontcover&img=1&zoom=1&vid=ISBN" + isbn13)
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
                if (body != null && body.length >= MIN_BYTES && type != null && "image".equals(type.getType()) && !isPlaceholder(body)) {
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
        return cached(isbn13, candidates(isbn13, null));
    }

    /** Cover of a Google Books volume that has no ISBN in Google's answer (often self-published titles). */
    public Optional<Path> googleCover(String volumeId) {
        if (volumeId == null || !volumeId.matches("[A-Za-z0-9_-]{6,20}")) {
            return Optional.empty();
        }
        return cached("g-" + volumeId,
                List.of("https://books.google.com/books/content?id=" + volumeId + "&printsec=frontcover&img=1&zoom=1&fife=w600"));
    }

    /**
     * A source's "no cover" card (Google: a plain grey card with two faint stripes) rather than a cover: the picture is
     * nearly one shade. Real covers, even white or black ones, carry a title in contrast.
     */
    static boolean isPlaceholder(byte[] image) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(image));
            return img != null && luminanceSpread(img) < PLACEHOLDER_SPREAD;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isPlaceholder(Path file) {
        try {
            return isPlaceholder(Files.readAllBytes(file));
        } catch (IOException e) {
            return false;
        }
    }

    private static final double PLACEHOLDER_SPREAD = 12;

    /** Standard deviation of brightness over a coarse grid of the picture. */
    private static double luminanceSpread(java.awt.image.BufferedImage img) {
        int n = 0;
        double sum = 0, sq = 0;
        for (int y = 0; y < img.getHeight(); y += Math.max(1, img.getHeight() / 48)) {
            for (int x = 0; x < img.getWidth(); x += Math.max(1, img.getWidth() / 32)) {
                int rgb = img.getRGB(x, y);
                double l = (((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255)) / 3.0;
                sum += l;
                sq += l * l;
                n++;
            }
        }
        double mean = sum / n;
        return Math.sqrt(Math.max(0, sq / n - mean * mean));
    }

    /** An author's portrait from Open Library. */
    public Optional<Path> authorPhoto(String olid) {
        if (olid == null || !olid.matches("OL\\d+A")) {
            return Optional.empty();
        }
        return cached("a-" + olid, List.of("https://covers.openlibrary.org/a/olid/" + olid + "-M.jpg?default=false"));
    }

    /** Fetched once into the catalog folder under {@code key}; a miss is remembered for a day. */
    private Optional<Path> cached(String key, List<String> urls) {
        for (String ext : List.of("jpg", "png")) {
            Path p = catalogDir.resolve(key + "." + ext);
            if (Files.isRegularFile(p)) {
                return Optional.of(p);
            }
        }
        java.time.Instant missed = misses.get(key);
        if (missed != null && missed.isAfter(java.time.Instant.now().minus(java.time.Duration.ofDays(1)))) {
            return Optional.empty();
        }
        Optional<Image> img = download(urls);
        if (img.isEmpty()) {
            misses.put(key, java.time.Instant.now());
            return Optional.empty();
        }
        try {
            Path p = catalogDir.resolve(key + "." + img.get().ext());
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

    /**
     * The picture to show: a publisher's 3D mockup cut down to its front (see CoverFlattener), else the file itself.
     * Worked out once per file and kept next to it; the original is never touched.
     */
    public Path flat(Path original) {
        String name = original.getFileName().toString();
        Path cut = flatDir.resolve(name + ".jpg");
        Path same = flatDir.resolve(name + ".same");
        if (Files.isRegularFile(cut)) {
            return cut;
        }
        if (Files.exists(same)) {
            return original;
        }
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(original.toFile());
            java.awt.image.BufferedImage front = img == null ? null : CoverFlattener.flatten(img);
            if (front == null || front == img) {
                Files.createFile(same);
                return original;
            }
            javax.imageio.ImageIO.write(front, "jpg", cut.toFile());
            return cut;
        } catch (IOException e) {
            log.debug("Cover {} not checked for a mockup: {}", name, e.getMessage());
            return original;
        }
    }

    /**
     * Prepares every stored cover (books, wishes, search hits) once, so covers saved before mockups were cut show
     * their front without a slow first request. Returns how many were cut in this run; already checked ones are skipped.
     */
    public int flattenAll() {
        int cut = 0;
        for (Path d : List.of(dir, catalogDir)) {
            try (var files = Files.list(d)) {
                for (Path p : files.filter(Files::isRegularFile).toList()) {
                    boolean before = Files.exists(flatDir.resolve(p.getFileName() + ".jpg"));
                    if (!before && !flat(p).equals(p)) {
                        cut++;
                    }
                }
            } catch (IOException e) {
                log.warn("Could not prepare covers in {}: {}", d, e.getMessage());
            }
        }
        return cut;
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void flattenAllInTheBackground() {
        Thread.ofVirtual().name("cover-flatten").start(() -> {
            int cut = flattenAll();
            if (cut > 0) {
                log.info("Cut {} publisher mockup(s) down to their front", cut);
            }
        });
    }

    public void delete(String file) {
        path(file).ifPresent(p -> {
            try {
                Files.deleteIfExists(flatDir.resolve(p.getFileName() + ".jpg"));
                Files.deleteIfExists(flatDir.resolve(p.getFileName() + ".same"));
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.warn("Could not delete cover {}", p);
            }
        });
    }
}
