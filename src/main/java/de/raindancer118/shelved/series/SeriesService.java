package de.raindancer118.shelved.series;

import de.raindancer118.shelved.ai.LibraryAi;
import de.raindancer118.shelved.book.Book;
import de.raindancer118.shelved.book.BookRepository;
import de.raindancer118.shelved.book.BookService;
import de.raindancer118.shelved.book.Names;
import de.raindancer118.shelved.book.NotFoundException;
import de.raindancer118.shelved.metadata.WikidataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Which series a book belongs to and which volumes exist.
 * Free sources answer first: library catalogues (DNB, K10plus, Library of Congress), Wikidata, Open Library and
 * Hardcover. Claude is only asked for what none of them knows, batched into one call for books,
 * and one call per unknown series when the user opens it.
 */
@Service
public class SeriesService {

    private static final Logger log = LoggerFactory.getLogger(SeriesService.class);
    private static final Duration WIKIDATA_REFRESH = Duration.ofDays(30);
    static final String WIKIDATA = "WIKIDATA";
    static final String CATALOG = "CATALOG";
    static final String CLAUDE = "CLAUDE";
    static final String HARDCOVER = "HARDCOVER";
    private static final String HARDCOVER_REF = "hardcover:";

    public record Volume(int number, String title, Book book) {
    }

    public record SeriesView(String key, String name, String author, List<Book> owned, List<Volume> volumes,
                             int missingCount, Integer knownTotal, String catalogStatus, String catalogSource,
                             String catalogNote) {
    }

    private final BookRepository books;
    private final SeriesCatalogRepository catalogs;
    private final WikidataClient wikidata;
    private final de.raindancer118.shelved.metadata.DnbClient dnb;
    private final de.raindancer118.shelved.metadata.K10plusClient k10plus;
    private final de.raindancer118.shelved.metadata.LocClient loc;
    private final de.raindancer118.shelved.metadata.OpenLibraryClient openLibrary;
    private final de.raindancer118.shelved.metadata.HardcoverClient hardcover;
    private final LibraryAi ai;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final Executor executor;
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    public SeriesService(BookRepository books, SeriesCatalogRepository catalogs, WikidataClient wikidata,
                         de.raindancer118.shelved.metadata.DnbClient dnb,
                         de.raindancer118.shelved.metadata.K10plusClient k10plus,
                         de.raindancer118.shelved.metadata.LocClient loc,
                         de.raindancer118.shelved.metadata.OpenLibraryClient openLibrary,
                         de.raindancer118.shelved.metadata.HardcoverClient hardcover, LibraryAi ai,
                         JsonMapper json, TransactionTemplate tx, @Qualifier("aiExecutor") Executor executor) {
        this.dnb = dnb;
        this.k10plus = k10plus;
        this.loc = loc;
        this.openLibrary = openLibrary;
        this.hardcover = hardcover;
        this.books = books;
        this.catalogs = catalogs;
        this.wikidata = wikidata;
        this.ai = ai;
        this.json = json;
        this.tx = tx;
        this.executor = executor;
    }

    public static String key(String seriesName, String author) {
        String surname = author == null ? "" : Names.collationKey(Names.sortForm(author).split(",")[0]);
        return Names.collationKey(seriesName).replaceAll("[^\\p{L}\\p{N}]+", "-") + "--" + surname.replaceAll("[^\\p{L}\\p{N}]+", "-");
    }

    public static String key(Book b) {
        return key(b.getSeriesName(), b.getAuthors().isEmpty() ? null : b.getAuthors().getFirst());
    }

    public List<SeriesView> overview(String owner) {
        Map<String, List<Book>> grouped = books.findByOwnerId(owner).stream()
                .filter(b -> b.getSeriesName() != null)
                .collect(Collectors.groupingBy(SeriesService::key, LinkedHashMap::new, Collectors.toList()));
        List<SeriesView> views = grouped.entrySet().stream()
                .filter(e -> confirmed(e.getValue()))
                .map(e -> view(e.getKey(), e.getValue())).toList();
        // catalogues from DNB and Wikidata are free: fetch missing or failed ones in the background
        views.stream().filter(v -> v.catalogStatus() == null || retryDue(v.key())).forEach(v -> requestCatalog(v, false));
        return views.stream()
                .sorted(Comparator.comparing((SeriesView v) -> v.missingCount() == 0).thenComparing(v -> Names.collationKey(v.name())))
                .toList();
    }

    /**
     * A single book that only a photo or Claude put into a series is not shown as a series:
     * those guesses produced dozens of one-book "series". Catalogue, Wikidata, the user, a second book
     * or a volume number above 1 make it real.
     */
    static boolean confirmed(List<Book> owned) {
        if (owned.size() > 1) {
            return true;
        }
        Book b = owned.getFirst();
        return b.getSeriesSource() == de.raindancer118.shelved.book.SeriesSource.MANUAL
                || b.getSeriesSource() == de.raindancer118.shelved.book.SeriesSource.CATALOG
                || b.getSeriesSource() == de.raindancer118.shelved.book.SeriesSource.WIKIDATA
                || b.getSeriesSource() == de.raindancer118.shelved.book.SeriesSource.COMMUNITY
                || (b.getSeriesVolume() != null && b.getSeriesVolume() > 1);
    }

    /** Opening a series is the moment where an unknown catalogue may be completed by Claude. */
    public SeriesView detail(String owner, String key) {
        SeriesView v = view(key, owned(owner, key));
        if (v.catalogStatus() == null || isStale(key) || retryDue(key)) {
            requestCatalog(v, true);
            v = view(key, owned(owner, key));
        }
        return v;
    }

    /** Explicit "check again", always allowed to ask Claude if Wikidata has nothing. */
    public SeriesView refresh(String owner, String key) {
        SeriesView v = view(key, owned(owner, key));
        requestCatalog(v, true);
        return view(key, owned(owner, key));
    }

    private List<Book> owned(String owner, String key) {
        List<Book> owned = books.findByOwnerId(owner).stream()
                .filter(b -> b.getSeriesName() != null && key.equals(key(b)))
                .toList();
        if (owned.isEmpty()) {
            throw new NotFoundException("Reihe");
        }
        return owned;
    }

    private List<SeriesView> overviewViews(String owner) {
        return books.findByOwnerId(owner).stream()
                .filter(b -> b.getSeriesName() != null)
                .collect(Collectors.groupingBy(SeriesService::key, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream().filter(e -> confirmed(e.getValue()))
                .map(e -> view(e.getKey(), e.getValue())).toList();
    }

    /** A failed lookup is tried again after an hour (sources and rules change); a found list after 30 days. */
    private boolean retryDue(String key) {
        return catalogs.findById(key)
                .filter(c -> c.getStatus() == SeriesCatalogEntry.Status.FAILED)
                .map(c -> c.getFetchedAt().isBefore(Instant.now().minus(Duration.ofHours(1))))
                .orElse(false);
    }

    private boolean isStale(String key) {
        return catalogs.findById(key)
                .filter(c -> WIKIDATA.equals(c.getSource()) || HARDCOVER.equals(c.getSource()) || c.getStatus() == SeriesCatalogEntry.Status.FAILED)
                .map(c -> c.getFetchedAt().isBefore(Instant.now().minus(WIKIDATA_REFRESH)))
                .orElse(false);
    }

    private SeriesView view(String key, List<Book> owned) {
        Book first = owned.getFirst();
        String author = first.getAuthors().isEmpty() ? null : first.getAuthors().getFirst();
        Optional<SeriesCatalogEntry> entry = catalogs.findById(key);
        List<SeriesCatalogVolume> catalog = entry
                .filter(c -> c.getStatus() == SeriesCatalogEntry.Status.DONE)
                .map(c -> json.readValue(c.getVolumesJson(), new TypeReference<List<SeriesCatalogVolume>>() {
                }))
                .orElse(List.of());
        Map<Integer, Book> byVolume = owned.stream().filter(b -> b.getSeriesVolume() != null)
                .collect(Collectors.toMap(Book::getSeriesVolume, b -> b, (a, b) -> a));
        List<SeriesGaps.Missing> missing = SeriesGaps.missing(byVolume.keySet(), catalog);

        Map<Integer, Volume> volumes = new TreeMap<>();
        catalog.forEach(v -> volumes.put(v.number(), new Volume(v.number(), v.title(), byVolume.get(v.number()))));
        byVolume.forEach((n, b) -> volumes.put(n, new Volume(n, b.getTitle(), b)));
        missing.forEach(m -> volumes.putIfAbsent(m.number(), new Volume(m.number(), m.title(), null)));

        return new SeriesView(key, first.getSeriesName(), author,
                owned.stream().sorted(Comparator.comparing(Book::getSeriesVolume, Comparator.nullsLast(Comparator.naturalOrder()))).toList(),
                List.copyOf(volumes.values()), missing.size(), catalog.isEmpty() ? null : catalog.size(),
                entry.map(c -> c.getStatus().name()).orElse(null),
                entry.map(SeriesCatalogEntry::getSource).orElse(null),
                entry.map(SeriesCatalogEntry::getError).orElse(null));
    }

    private void requestCatalog(SeriesView v, boolean claudeAllowed) {
        if (!busy.add(v.key())) {
            return;
        }
        String ref = v.owned().stream().map(Book::getSeriesRef).filter(r -> r != null && !r.startsWith(HARDCOVER_REF)).findFirst().orElse(null);
        String hcRef = v.owned().stream().map(Book::getSeriesRef).filter(r -> r != null && r.startsWith(HARDCOVER_REF)).findFirst().orElse(null);
        List<String> isbns = v.owned().stream().map(Book::getIsbn13).filter(Objects::nonNull).limit(2).toList();
        String language = v.owned().stream().map(Book::getLanguage).filter(Objects::nonNull).findFirst().orElse("ger");
        tx.executeWithoutResult(s -> {
            SeriesCatalogEntry e = catalogs.findById(v.key()).orElseGet(() -> new SeriesCatalogEntry(v.key(), v.name(), v.author()));
            e.pending();
            e.setSeriesRef(ref);
            catalogs.save(e);
        });
        executor.execute(() -> {
            try {
                fetchCatalog(v.key(), v.name(), v.author(), ref, hcRef, isbns, language, claudeAllowed);
            } finally {
                busy.remove(v.key());
            }
        });
    }

    void fetchCatalog(String key, String name, String author, String ref, String hardcoverRef, List<String> isbns,
                      String language, boolean claudeAllowed) {
        try {
            boolean german = language == null || language.startsWith("ger") || language.startsWith("de");
            // libraries know German titles and publisher numbering, Wikidata and Hardcover often know more volumes
            List<SeriesCatalogVolume> fromDnb = dnb.seriesCatalog(name, author);
            List<SeriesCatalogVolume> fromK10plus = k10plus.seriesCatalog(name, author);
            List<SeriesCatalogVolume> library = fromK10plus.size() > fromDnb.size() ? fromK10plus : fromDnb;
            List<SeriesCatalogVolume> fromWikidata = ref == null ? List.of() : wikidata.catalog(ref, language);
            List<SeriesCatalogVolume> fromHardcover = hardcoverSeries(name, author, hardcoverRef, isbns)
                    .map(id -> hardcover.catalog(id, german)).orElse(List.of());
            boolean hardcoverWins = fromHardcover.size() > fromWikidata.size();
            List<SeriesCatalogVolume> community = hardcoverWins ? fromHardcover : fromWikidata;
            List<SeriesCatalogVolume> vols;
            String source;
            if (!library.isEmpty() && (library.size() >= community.size() || german && library.size() * 4 >= community.size() * 3)) {
                vols = library;
                source = CATALOG;
            } else {
                vols = community;
                source = hardcoverWins ? HARDCOVER : WIKIDATA;
            }
            String note = null;
            if (vols.isEmpty() && claudeAllowed && ai.available()) {
                LibraryAi.SeriesCatalog c = ai.seriesCatalog(name, author, language);
                vols = c.volumes().stream()
                        .filter(x -> x.number() > 0 && x.title() != null && !x.title().isBlank())
                        .map(x -> new SeriesCatalogVolume(x.number(), x.title().trim()))
                        .distinct()
                        .sorted(Comparator.comparingInt(SeriesCatalogVolume::number))
                        .toList();
                source = CLAUDE;
                note = c.note();
            }
            if (vols.isEmpty()) {
                tx.executeWithoutResult(s -> catalogs.findById(key).ifPresent(e -> e.failed(
                        claudeAllowed ? "Keine Bandliste gefunden" : "Bandliste noch unbekannt")));
                return;
            }
            String volumesJson = json.writeValueAsString(vols);
            String src = source;
            String n = note;
            tx.executeWithoutResult(s -> catalogs.findById(key).ifPresent(e -> e.done(volumesJson, n, src)));
        } catch (RuntimeException e) {
            log.warn("Series catalog for {} failed: {}", name, e.getMessage());
            tx.executeWithoutResult(s -> catalogs.findById(key).ifPresent(en -> en.failed(e.getMessage())));
        }
    }

    /** Hardcover's series id: remembered from a book, found through an owned edition, or searched by name. */
    private Optional<Long> hardcoverSeries(String name, String author, String hardcoverRef, List<String> isbns) {
        if (hardcoverRef != null) {
            return Optional.of(Long.parseLong(hardcoverRef.substring(HARDCOVER_REF.length())));
        }
        for (String isbn : isbns) {
            Optional<de.raindancer118.shelved.metadata.HardcoverClient.SeriesMatch> m = hardcover.seriesByIsbn(isbn);
            if (m.isPresent()) {
                return Optional.of(m.get().seriesId());
            }
        }
        return hardcover.findSeries(name, author);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onBooksAdded(BookService.BooksAdded event) {
        executor.execute(() -> resolvePending(event.ownerId()));
    }

    /** "Check series again": everything the user didn't set goes through the sources once more. */
    public int recheck(String owner) {
        Integer n = tx.execute(s -> {
            List<Book> open = books.findByOwnerId(owner).stream()
                    .filter(b -> b.getSeriesSource() != de.raindancer118.shelved.book.SeriesSource.MANUAL).toList();
            open.forEach(b -> {
                if (b.getSeriesSource() == de.raindancer118.shelved.book.SeriesSource.CATALOG) {
                    // catalogue values are reproducible: if the current rules don't confirm them, they were wrong
                    b.setSeries(null, null, null);
                }
                b.setSeriesChecked(false);
            });
            return open.size();
        });
        executor.execute(() -> {
            resolvePending(owner);
            // fresh look at every series list too (free sources only)
            overviewViews(owner).forEach(v -> requestCatalog(v, false));
        });
        return n == null ? 0 : n;
    }

    /** After an update every owner's unchecked books (e.g. the one-time re-check of V6) are resolved again. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void resolveAllOnStartup() {
        retryOpenBooks();
    }

    /** Books left open because a catalogue was unreachable get another round. */
    @org.springframework.scheduling.annotation.Scheduled(initialDelayString = "PT20M", fixedDelayString = "PT20M")
    public void retryOpenBooks() {
        List<String> owners = tx.execute(s -> books.findAll().stream().filter(b -> !b.isSeriesChecked())
                .map(Book::getOwnerId).distinct().toList());
        if (owners != null) {
            owners.forEach(o -> executor.execute(() -> resolvePending(o)));
        }
    }

    /**
     * Finds the series of unchecked books, most trustworthy source first:
     * the national library's record of the edition, Wikidata, the photo's reading, and Claude last
     * (one batched call, only numbered answers count). Catalogued and hand-made series are never changed.
     */
    public void resolvePending(String owner) {
        if (!busy.add("resolve:" + owner)) {
            return;
        }
        try {
            Set<java.util.UUID> attempted = new java.util.HashSet<>();
            List<Book> pending;
            while (!(pending = tx.execute(s -> books.findByOwnerIdAndSeriesCheckedFalse(owner).stream()
                    .filter(b -> !attempted.contains(b.getId())).limit(40).toList())).isEmpty()) {
                pending.forEach(b -> attempted.add(b.getId()));
                Map<String, Resolution> found = new LinkedHashMap<>();
                Set<String> deferred = new java.util.HashSet<>();
                List<Book> unknown = new ArrayList<>();
                for (Book b : pending) {
                    if (b.seriesSettled()) {
                        continue;
                    }
                    Optional<Resolution> r;
                    try {
                        r = fromCatalogue(b).or(() -> fromWikidata(b)).or(() -> fromCommunity(b));
                    } catch (de.raindancer118.shelved.metadata.CatalogUnavailableException e) {
                        // a weaker source must not win just because the library didn't answer: try again later
                        deferred.add(b.getId().toString());
                        continue;
                    }
                    r.ifPresent(x -> found.put(b.getId().toString(), x));
                    boolean germanBook = b.getLanguage() == null || b.getLanguage().startsWith("ger");
                    boolean onlyEnglishName = r.isPresent() && r.get().keepGermanName() && b.getSeriesName() == null && germanBook;
                    if (r.isEmpty() && b.getSeriesName() == null || onlyEnglishName) {
                        unknown.add(b); // for an English-only Wikidata name Claude just supplies the German one
                    }
                }
                Map<String, LibraryAi.SeriesGuess> guesses = new LinkedHashMap<>();
                if (!unknown.isEmpty() && ai.available()) {
                    try {
                        ai.resolveSeries(unknown.stream().map(b -> new LibraryAi.BookRef(b.getId().toString(), b.getTitle(),
                                        b.getAuthors().isEmpty() ? null : b.getAuthors().getFirst(), b.getPublisher(), b.getPublishedYear())).toList())
                                .forEach(g -> guesses.put(g.id(), g));
                    } catch (RuntimeException e) {
                        log.warn("Series fallback via Claude failed: {}", e.getMessage());
                    }
                }
                List<Book> batch = pending;
                tx.executeWithoutResult(s -> batch.stream().filter(p -> !deferred.contains(p.getId().toString()))
                        .forEach(p -> books.findById(p.getId()).ifPresent(b -> {
                    if (!b.seriesSettled()) {
                        Resolution r = found.get(b.getId().toString());
                        LibraryAi.SeriesGuess g = guesses.get(b.getId().toString());
                        if (r != null && g != null && r.keepGermanName() && b.getSeriesName() == null
                                && g.series() != null && !g.series().isBlank()) {
                            b.setSeries(g.series(), r.volume() != null ? r.volume() : (g.volume() > 0 ? g.volume() : null),
                                    r.source());
                            b.setSeriesRef(r.ref());
                        } else if (r != null) {
                            r.applyTo(b);
                        } else if (g != null && g.series() != null && !g.series().isBlank() && g.volume() > 0
                                && b.getSeriesName() == null) {
                            b.setSeries(g.series(), g.volume(), de.raindancer118.shelved.book.SeriesSource.AI);
                        } else if (b.getSeriesName() != null && de.raindancer118.shelved.metadata.MarcSruClient.notALiterarySeries(b.getSeriesName())
                                && b.getSeriesSource() != de.raindancer118.shelved.book.SeriesSource.MANUAL) {
                            // "Werkausgabe 7", "Klassiker in leichter Sprache" read from a spine: numbered, but no series
                            b.setSeries(null, null, null);
                        } else if (b.getSeriesName() != null && b.getSeriesSource() == null) {
                            // recognised before sources were tracked and confirmed by nothing now: a guess
                            b.setSeries(b.getSeriesName(), b.getSeriesVolume(), de.raindancer118.shelved.book.SeriesSource.PHOTO);
                        }
                    }
                    b.setSeriesChecked(true);
                })));
            }
        } finally {
            busy.remove("resolve:" + owner);
        }
    }

    record Resolution(String name, Integer volume, String ref, de.raindancer118.shelved.book.SeriesSource source, boolean keepGermanName) {
        void applyTo(Book b) {
            // an English Wikidata label must not replace a German name read from the spine
            String n = keepGermanName && b.getSeriesName() != null ? b.getSeriesName() : name;
            Integer v = volume != null ? volume : b.getSeriesVolume();
            b.setSeries(n, v, source);
            if (ref != null) {
                b.setSeriesRef(ref);
            }
        }
    }

    private Optional<Resolution> fromCatalogue(Book b) {
        String author = b.getAuthors().isEmpty() ? null : b.getAuthors().getFirst();
        String isbn = b.getIsbn13();
        Optional<de.raindancer118.shelved.metadata.BookMetadata> record = isbn == null ? Optional.empty()
                : dnb.byIsbn(isbn).filter(m -> m.seriesName() != null)
                .or(() -> k10plus.byIsbn(isbn).filter(m -> m.seriesName() != null))
                // German-language ISBNs (978-3) are practically never in the Library of Congress
                .or(() -> isbn.startsWith("9783") ? Optional.empty() : loc.byIsbn(isbn).filter(m -> m.seriesName() != null));
        if (record.isEmpty()) {
            // e-book ISBN or no ISBN: the printed edition with the same title tells the series too
            record = sameTitle(dnb.search(b.getTitle(), author), b)
                    .or(() -> sameTitle(k10plus.search(b.getTitle(), author), b));
        }
        return record.map(m -> new Resolution(m.seriesName(), m.seriesVolume(), null, de.raindancer118.shelved.book.SeriesSource.CATALOG, false));
    }

    private static Optional<de.raindancer118.shelved.metadata.BookMetadata> sameTitle(
            List<de.raindancer118.shelved.metadata.BookMetadata> hits, Book b) {
        return hits.stream()
                .filter(m -> m.seriesName() != null && de.raindancer118.shelved.metadata.Similarity.edition(b.getTitle(), m) >= 0.8)
                .findFirst();
    }

    /** Reader communities, by ISBN only: a title search there matches too loosely. */
    private Optional<Resolution> fromCommunity(Book b) {
        String isbn = b.getIsbn13();
        if (isbn == null) {
            return Optional.empty();
        }
        boolean germanBook = b.getLanguage() == null || b.getLanguage().startsWith("ger");
        return openLibrary.seriesByIsbn(isbn)
                .map(h -> new Resolution(h.name(), h.volume(), null, de.raindancer118.shelved.book.SeriesSource.COMMUNITY, false))
                // Hardcover names series after the original work, mostly English
                .or(() -> hardcover.seriesByIsbn(isbn).map(m -> new Resolution(m.name(), m.volume(), HARDCOVER_REF + m.seriesId(),
                        de.raindancer118.shelved.book.SeriesSource.COMMUNITY, germanBook)));
    }

    private Optional<Resolution> fromWikidata(Book b) {
        String author = b.getAuthors().isEmpty() ? null : b.getAuthors().getFirst();
        return wikidata.findSeries(b.getTitle(), author)
                .map(m -> new Resolution(m.seriesName(), m.volume(), m.seriesId(), de.raindancer118.shelved.book.SeriesSource.WIKIDATA,
                        !m.german()));
    }
}
