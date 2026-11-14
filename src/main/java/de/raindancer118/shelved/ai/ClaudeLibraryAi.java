package de.raindancer118.shelved.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.WebSearchTool20260209;
import de.raindancer118.shelved.config.ShelvedProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ClaudeLibraryAi implements LibraryAi {

    private static final Logger log = LoggerFactory.getLogger(ClaudeLibraryAi.class);
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private static final String SHELF_SYSTEM = """
            You read book spines on a photo of a private home bookcase. The photo may show one or several shelf boards.
            Give every book the board it stands on: row 1 is the topmost board visible in the photo, counting downwards.
            List every book exactly once, top board first, each board from left to right. Books in the overlap between
            two image segments appear twice in the images; list them once. Include books lying flat or partly hidden.
            Skip books that are cut off at the left or right edge of the photo and clearly stand in a neighbouring bookcase.
            Write titles in the language printed on the spine (German editions keep their German titles).
            You may complete a partly readable title or add an author that is not printed only when you are sure
            which book it is. Never invent books. If a spine is unreadable, still list it with confidence "low".
            For series, name the literary series as readers know it (e.g. "Scheibenwelt", "Harry Potter"),
            not a publisher imprint such as "Piper Fantasy" or "Fischer Taschenbuch".""";


    private static final String SINGLE_SYSTEM = """
            You identify books from a photo a user took of a book (cover, spine or title page) or a small stack.
            List each clearly shown book once. Write titles in the language printed on the book.
            You may complete a title or author only when you are sure which book it is. Never invent books.
            For series, name the literary series as readers know it, not a publisher imprint.""";

    private final ShelvedProperties.Anthropic config;
    private final AnthropicClient client;

    public ClaudeLibraryAi(ShelvedProperties props) {
        this.config = props.anthropic();
        this.client = config.configured() ? AnthropicOkHttpClient.builder().apiKey(config.apiKey()).build() : null;
    }

    @Override
    public boolean available() {
        return client != null;
    }

    @Override
    public ShelfReading readShelf(List<byte[]> jpegTiles, boolean topToBottom) {
        List<ContentBlockParam> content = new ArrayList<>();
        for (int i = 0; i < jpegTiles.size(); i++) {
            String label = jpegTiles.size() == 1 ? "The photo:"
                    : topToBottom ? (i == 0 ? "Top half of the photo:" : "Bottom half of the photo (overlaps the top half):")
                    : "Segment " + (i + 1) + " of " + jpegTiles.size() + ", left to right, overlapping:";
            content.add(text(label));
            content.add(image(jpegTiles.get(i)));
        }
        content.add(text("Read all spines."));
        // reading spines needs eyes, not deliberation: low effort keeps the cost per photo at a few cents
        return call(ShelfReading.class, SHELF_SYSTEM, content, OutputConfig.Effort.LOW, false, 32000);
    }

    @Override
    public ShelfReading readSingleBook(byte[] jpeg) {
        return call(ShelfReading.class, SINGLE_SYSTEM, List.of(image(jpeg), text("Which book(s) is this?")),
                OutputConfig.Effort.LOW, false, 8000);
    }

    @Override
    public List<SeriesGuess> resolveSeries(List<BookRef> books) {
        if (books.isEmpty()) {
            return List.of();
        }
        String list = books.stream()
                .map(b -> "- id=%s | %s | %s | %s %s".formatted(b.id(), b.title(), nz(b.author()), nz(b.publisher()),
                        b.year() == null ? "" : b.year()))
                .collect(Collectors.joining("\n"));
        String prompt = """
                For each book below, say which literary series it belongs to and its volume number in the series'
                usual reading order (the numbering the publisher of this edition uses, e.g. Scheibenwelt numbering for
                German Pratchett editions). Use the series name in the language of the title. Standalone books get an
                empty series and volume 0. Omnibus editions get the number of their first contained volume.

                """ + list;
        SeriesGuesses r = call(SeriesGuesses.class, null, List.of(text(prompt)), OutputConfig.Effort.LOW, false, 8000);
        return r.books() == null ? List.of() : r.books();
    }

    @Override
    public SeriesCatalog seriesCatalog(String seriesName, String author, String language) {
        String lang = language == null || language.startsWith("ger") || language.startsWith("de") ? "German" : "the original language";
        String prompt = """
                List every volume of the book series "%s"%s, numbered in the usual reading order,
                with each volume's title as published in %s. Search the web to check numbering and the latest volumes.
                Include only main novels of the series that carry a number, no short-story anthologies or companion
                books unless the publisher numbers them as part of the series."""
                .formatted(seriesName, author == null || author.isBlank() ? "" : " by " + author, lang);
        return call(SeriesCatalog.class, null, List.of(text(prompt)), OutputConfig.Effort.MEDIUM, true, 16000);
    }

    private <T> T call(Class<T> type, String system, List<ContentBlockParam> content, OutputConfig.Effort effort,
                       boolean webSearch, long maxTokens) {
        if (client == null) {
            throw new AiException("Kein Anthropic-API-Key konfiguriert");
        }
        List<com.anthropic.models.messages.Message> assistantTurns = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            StructuredMessageCreateParams.Builder<T> b = MessageCreateParams.builder()
                    .model(config.model())
                    .maxTokens(maxTokens)
                    .outputConfig(StructuredOutputConfig.<T>builder().format(type).effort(effort).build())
                    .addUserMessageOfContentBlockParams(content)
                    .putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
            if (system != null) {
                b.system(system);
            }
            if (webSearch) {
                b.addTool(WebSearchTool20260209.builder().maxUses(6L).build());
            }
            assistantTurns.forEach(b::addMessage);
            StructuredMessage<T> response;
            try {
                response = client.messages().create(b.build());
            } catch (RateLimitException e) {
                throw new AiException("Claude ist gerade ausgelastet, bitte gleich nochmal versuchen", e);
            } catch (AnthropicServiceException e) {
                throw new AiException("Claude-Fehler: " + e.getMessage(), e);
            }
            StopReason stop = response.stopReason().orElse(null);
            log.info("Claude {} round {}: stop={} in={} out={}", type.getSimpleName(), round, stop,
                    response.usage().inputTokens(), response.usage().outputTokens());
            if (StopReason.REFUSAL.equals(stop)) {
                throw new AiException("Claude hat die Anfrage abgelehnt");
            }
            if (StopReason.PAUSE_TURN.equals(stop)) {
                assistantTurns.add(response.rawMessage());
                continue;
            }
            if (StopReason.MAX_TOKENS.equals(stop)) {
                throw new AiException("Antwort zu lang (max_tokens erreicht)");
            }
            return response.content().stream()
                    .flatMap(c -> c.text().stream())
                    .map(StructuredTextBlock::text)
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> new AiException("Claude hat keine auswertbare Antwort geliefert"));
        }
        throw new AiException("Claude ist nicht fertig geworden");
    }

    private static ContentBlockParam image(byte[] jpeg) {
        return ContentBlockParam.ofImage(ImageBlockParam.builder()
                .source(Base64ImageSource.builder()
                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                        .data(Base64.getEncoder().encodeToString(jpeg))
                        .build())
                .build());
    }

    private static ContentBlockParam text(String s) {
        return ContentBlockParam.ofText(TextBlockParam.builder().text(s).build());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
