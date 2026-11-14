package de.raindancer118.shelved.metadata;

import de.raindancer118.shelved.book.Names;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Combines the free catalogues. No AI involved. */
@Service
public class MetadataService {

    private final DnbClient dnb;
    private final GoogleBooksClient google;
    private final OpenLibraryClient openLibrary;
    private final RestClient http;

    public MetadataService(DnbClient dnb, GoogleBooksClient google, OpenLibraryClient openLibrary, RestClient externalHttp) {
        this.dnb = dnb;
        this.google = google;
        this.openLibrary = openLibrary;
        this.http = externalHttp;
    }

    /** DNB first (German editions, bound price), Google Books for blurb/cover/pages, Open Library as last resort. */
    public Optional<BookMetadata> byIsbn(String isbn13) {
        Optional<BookMetadata> d = dnb.byIsbn(isbn13);
        Optional<BookMetadata> g = google.byIsbn(isbn13);
        if (d.isPresent()) {
            BookMetadata m = g.map(x -> merge(d.get(), x)).orElse(d.get());
            if (m.pageCount() == null) {
                m = openLibrary.byIsbn(isbn13).map(ol -> merge(d.get(), ol)).orElse(m);
            }
            return Optional.of(m);
        }
        if (g.isPresent()) {
            return Optional.of(openLibrary.byIsbn(isbn13).map(ol -> merge(g.get(), ol)).orElse(g.get()));
        }
        return openLibrary.byIsbn(isbn13);
    }

    /** Candidates for a title/author we only know from a spine or a typed query, best first. */
    public List<BookMetadata> search(String title, String author, String publisherHint) {
        List<BookMetadata> all = new ArrayList<>(dnb.search(title, author));
        all.addAll(google.search(title, author));
        if (all.isEmpty()) {
            all.addAll(openLibrary.search(title, author));
        }
        Set<String> seen = new HashSet<>();
        return all.stream()
                .filter(m -> m.isbn13() == null || seen.add(m.isbn13()))
                .sorted(Comparator.comparingDouble((BookMetadata m) -> score(m, title, author, publisherHint)).reversed())
                .toList();
    }

    public static double score(BookMetadata m, String title, String author, String publisherHint) {
        double s = Similarity.title(title, m.title()) * 3;
        if (author != null && !author.isBlank() && !m.authors().isEmpty()) {
            s += Similarity.surname(author, m.authors().getFirst()) ? 1.5 : 0;
        }
        if (publisherHint != null && !publisherHint.isBlank() && m.publisher() != null
                && Names.collationKey(m.publisher()).contains(Names.collationKey(publisherHint))) {
            s += 1;
        }
        if ("DNB".equals(m.source())) {
            s += 0.3; // has price and German cover
        }
        if (m.isbn13() != null) {
            s += 0.3;
        }
        if (m.publishedYear() != null) {
            s += Math.min(0.2, Math.max(0, (m.publishedYear() - 1950) / 500.0)); // newer editions are what people own
        }
        return s;
    }

    /** The blurb: from the catalogue answer if there was one, else the DNB "Inhaltstext" page. */
    public Optional<String> description(BookMetadata m) {
        if (m.description() != null && !m.description().isBlank()) {
            return Optional.of(m.description());
        }
        if (m.descriptionUrl() == null) {
            return Optional.empty();
        }
        try {
            String html = http.get().uri(URI.create(m.descriptionUrl())).retrieve().body(String.class);
            if (html == null) {
                return Optional.empty();
            }
            String text = html.replaceAll("(?s)<head>.*?</head>", "")
                    .replaceAll("<br\\s*/?>", "\n")
                    .replaceAll("<[^>]+>", "")
                    .replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'")
                    .trim();
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }

    private static BookMetadata merge(BookMetadata primary, BookMetadata extra) {
        return new BookMetadata(primary.isbn13(), primary.title(), primary.titleSort(),
                primary.subtitle() != null ? primary.subtitle() : extra.subtitle(),
                primary.authors().isEmpty() ? extra.authors() : primary.authors(),
                primary.authorSort() != null ? primary.authorSort() : extra.authorSort(),
                primary.publisher() != null ? primary.publisher() : extra.publisher(),
                primary.publishedYear() != null ? primary.publishedYear() : extra.publishedYear(),
                primary.pageCount() != null ? primary.pageCount() : extra.pageCount(),
                primary.heightMm(), primary.format() != null ? primary.format() : extra.format(),
                primary.language() != null ? primary.language() : extra.language(),
                primary.listPriceCents(), primary.descriptionUrl(),
                primary.description() != null ? primary.description() : extra.description(),
                // Google renders covers at 600 px, the DNB/MVB ones are ~380 px; CoverStore falls back to DNB anyway
                "GOOGLE".equals(extra.source()) && extra.coverUrl() != null ? extra.coverUrl()
                        : primary.coverUrl() != null ? primary.coverUrl() : extra.coverUrl(),
                primary.source());
    }
}
