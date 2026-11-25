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
            For every spine also give its width as a share of its board's usable width between the side walls,
            measured on the photo as carefully as you can (a spine taking a twentieth of the board is 0.05).""";


    private static final String SINGLE_SYSTEM = """
            You identify books from a photo a user took of a book (cover, spine or title page) or a small stack.
            List each clearly shown book once. Write titles in the language printed on the book.
            You may complete a title or author only when you are sure which book it is. Never invent books.""";

    private static final String FACE_SYSTEM = """
            You locate one face of a physical book in a photo so it can be cut out and straightened.
            Faces: "back cover" (the rear board or jacket), "spine" (the narrow side with title and author, where the
            pages are bound), "fore edge" (the side opposite the spine showing the stacked page edges, often with a
            coloured or patterned Farbschnitt), all between the book's head (top) and tail (bottom).
            Give the four corners of exactly that face, measured as precisely as you can on the photo, as fractions of
            the photo's width and height. Head and tail follow the book, not the photo: if the book lies sideways, the
            head is where the top of the printed text or artwork points. Corners must lie on the face itself, never
            on background or on a neighbouring face. If the face is not clearly visible, answer found = false.""";

    /** Token usage of the most recent call, for logs and the model comparison. */
    public record Usage(String model, long inputTokens, long outputTokens) {
    }

    private volatile Usage lastUsage;
    private final ShelvedProperties.Anthropic config;
    private final AnthropicClient client;

    public ClaudeLibraryAi(ShelvedProperties props) {
        this.config = props.anthropic();
        this.client = config.configured() ? AnthropicOkHttpClient.builder().apiKey(config.apiKey()).build() : null;
    }

    public Usage lastUsage() {
        return lastUsage;
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
        return call(ShelfReading.class, SHELF_SYSTEM, content, OutputConfig.Effort.LOW, 32000);
    }

    @Override
    public ShelfReading readSingleBook(byte[] jpeg) {
        return call(ShelfReading.class, SINGLE_SYSTEM, List.of(image(jpeg), text("Which book(s) is this?")),
                OutputConfig.Effort.LOW, 8000);
    }

    @Override
    public FaceLocation locateFace(byte[] jpeg, String face) {
        return call(FaceLocation.class, FACE_SYSTEM, List.of(image(jpeg), text("Locate the " + face + " of the book.")),
                OutputConfig.Effort.LOW, 4000);
    }

    private <T> T call(Class<T> type, String system, List<ContentBlockParam> content, OutputConfig.Effort effort, long maxTokens) {
        if (client == null) {
            throw new AiException("Kein Anthropic-API-Key konfiguriert");
        }
        List<com.anthropic.models.messages.Message> assistantTurns = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            StructuredMessageCreateParams.Builder<T> b = MessageCreateParams.builder()
                    .model(config.model())
                    .maxTokens(maxTokens)
                    .outputConfig(StructuredOutputConfig.<T>builder().format(type).effort(effort).build())
                    .addUserMessageOfContentBlockParams(content);
            if (supportsServerFallback(config.model())) {
                b.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                        .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
            }
            if (system != null) {
                b.system(system);
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
            lastUsage = new Usage(config.model(), response.usage().inputTokens(), response.usage().outputTokens());
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

    /** Haiku has no server-side refusal fallback; sending the parameter there is an error. */
    static boolean supportsServerFallback(String model) {
        return model.startsWith("claude-opus-5") || model.startsWith("claude-fable-5") || model.equals("claude-sonnet-5-5");
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
