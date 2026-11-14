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
 * Wikidata answers first (free, our own processing). Claude is only asked for what Wikidata doesn't know,
 * batched into one call for books, and one call per unknown series when the user opens it.
 */
@Service
public class SeriesService {

    private static final Logger log = LoggerFactory.getLogger(SeriesService.class);
    private static final Duration WIKIDATA_REFRESH = Duration.ofDays(30);
    static final String WIKIDATA = "WIKIDATA";
    static final String CLAUDE = "CLAUDE";

    public record Volume(int number, String title, Book book) {
    }

    public record SeriesView(String key, String name, String author, List<Book> owned, List<Volume> volumes,
                             int missingCount, Integer knownTotal, String catalogStatus, String catalogSource,
                             String catalogNote) {
    }

    private final BookRepository books;
    private final SeriesCatalogRepository catalogs;
    private final WikidataClient wikidata;
    private final LibraryAi ai;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final Executor executor;
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    public SeriesService(BookRepository books, SeriesCatalogRepository catalogs, WikidataClient wikidata, LibraryAi ai,
                         JsonMapper json, TransactionTemplate tx, @Qualifier("aiExecutor") Executor executor) {
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
        List<SeriesView> views = grouped.entrySet().stream().map(e -> view(e.getKey(), e.getValue())).toList();
        // Wikidata catalogues are free: fetch the missing ones in the background so gap counts become complete
        views.stream().filter(v -> v.catalogStatus() == null).forEach(v -> requestCatalog(v, false));
        return views.stream()
                .sorted(Comparator.comparing((SeriesView v) -> v.missingCount() == 0).thenComparing(v -> Names.collationKey(v.name())))
                .toList();
    }

    /** Opening a series is the moment where an unknown catalogue may be completed by Claude. */
    public SeriesView detail(String owner, String key) {
        SeriesView v = view(key, owned(owner, key));
        if (v.catalogStatus() == null || isStale(key)) {
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

    private boolean isStale(String key) {
        return catalogs.findById(key)
                .filter(c -> WIKIDATA.equals(c.getSource()) || c.getStatus() == SeriesCatalogEntry.Status.FAILED)
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
        String ref = v.owned().stream().map(Book::getSeriesRef).filter(Objects::nonNull).findFirst().orElse(null);
        String language = v.owned().stream().map(Book::getLanguage).filter(Objects::nonNull).findFirst().orElse("ger");
        tx.executeWithoutResult(s -> {
            SeriesCatalogEntry e = catalogs.findById(v.key()).orElseGet(() -> new SeriesCatalogEntry(v.key(), v.name(), v.author()));
            e.pending();
            e.setSeriesRef(ref);
            catalogs.save(e);
        });
        executor.execute(() -> {
            try {
                fetchCatalog(v.key(), v.name(), v.author(), ref, language, claudeAllowed);
            } finally {
                busy.remove(v.key());
            }
        });
    }

    void fetchCatalog(String key, String name, String author, String ref, String language, boolean claudeAllowed) {
        try {
            List<SeriesCatalogVolume> vols = ref == null ? List.of() : wikidata.catalog(ref, language);
            String source = WIKIDATA;
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
                        claudeAllowed ? "Keine Bandliste gefunden" : "Wikidata kennt diese Reihe nicht")));
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

    @TransactionalEventListener(fallbackExecution = true)
    public void onBooksAdded(BookService.BooksAdded event) {
        executor.execute(() -> resolvePending(event.ownerId()));
    }

    /**
     * Finds the series of new books: Wikidata per book, then a single batched Claude call
     * for the books that neither Wikidata nor the shelf photo could place.
     */
    public void resolvePending(String owner) {
        if (!busy.add("resolve:" + owner)) {
            return;
        }
        try {
            List<Book> pending;
            while (!(pending = tx.execute(s -> books.findByOwnerIdAndSeriesCheckedFalse(owner).stream().limit(40).toList())).isEmpty()) {
                Map<String, WikidataClient.SeriesMatch> found = new LinkedHashMap<>();
                List<Book> unknown = new ArrayList<>();
                for (Book b : pending) {
                    String author = b.getAuthors().isEmpty() ? null : b.getAuthors().getFirst();
                    Optional<WikidataClient.SeriesMatch> m = wikidata.findSeries(b.getTitle(), author);
                    if (m.isPresent()) {
                        found.put(b.getId().toString(), m.get());
                    } else if (b.getSeriesName() == null) {
                        unknown.add(b);
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
                tx.executeWithoutResult(s -> batch.forEach(p -> books.findById(p.getId()).ifPresent(b -> {
                    String id = b.getId().toString();
                    WikidataClient.SeriesMatch m = found.get(id);
                    LibraryAi.SeriesGuess g = guesses.get(id);
                    if (m != null) {
                        b.setSeriesName(m.seriesName());
                        b.setSeriesVolume(m.volume());
                        b.setSeriesRef(m.seriesId());
                    } else if (g != null && g.series() != null && !g.series().isBlank() && b.getSeriesName() == null) {
                        b.setSeriesName(g.series());
                        b.setSeriesVolume(g.volume());
                    }
                    b.setSeriesChecked(true);
                })));
            }
        } finally {
            busy.remove("resolve:" + owner);
        }
    }
}
