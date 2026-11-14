package de.raindancer118.shelved.ai;

import de.raindancer118.shelved.config.ShelvedProperties;
import de.raindancer118.shelved.metadata.Similarity;
import de.raindancer118.shelved.scan.ImageTiler;
import de.raindancer118.shelved.scan.Images;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Costs real money: only runs with SHELVED_COMPARE_PHOTO set (never in CI).
 * Reads one shelf photo with each model and scores the titles against the books really on it.
 */
@EnabledIfEnvironmentVariable(named = "SHELVED_COMPARE_PHOTO", matches = ".+")
class ModelComparisonLiveTest {

    /** The 42 books on Tom's test photo, checked by hand. */
    static final List<String> TRUTH = List.of(
            "Feuerklingen", "Königsklingen", "Tote Mädchen lügen nicht", "Legends & Lattes", "Eisige Wellen",
            "Der Tag, an dem die Welt zerriss", "Das Mädchen, das die Zeit durchbrach", "Die Liebe, die den Anfang brachte",
            "Was die Magie verlangt", "Wie die Dunkelheit befiehlt", "The Cruel Prince", "Der Weg des Kämpfers",
            "Der Ring des Himmels", "Die Rückkehr des Kriegers", "The Serpent and the Wings of Night", "Die Karte der Welt",
            "Sam Hinkel und die Akademie für Ärger", "Sam Hinkel Der Ärger geht weiter", "The Lesson of Curses",
            "Magic Academy Das erste Jahr", "Magic Academy Die Kandidatin", "Magic Academy Die Prüfung",
            "Magic Academy Der letzte Kampf", "Die Beschenkte", "Der Schattenjäger-Codex", "City of Bones", "City of Ashes",
            "Catching Fire", "Never Be My Date", "Never Be My Enemy", "Never Be My Love", "The Floating City",
            "Die Queen und ich", "A Million Stars Above", "The Stranded", "Robinson Crusoe", "Zessalonn", "Gliss",
            "Drachenmeer", "Mysteria", "Ayani", "Sinkalion");

    static final Map<String, double[]> PRICE_PER_MTOK = Map.of(
            "claude-opus-5-5", new double[]{4, 20}, "claude-sonnet-5-5", new double[]{2, 10}, "claude-haiku-5-5", new double[]{0.10, 0.50});

    @Test
    void compareModels() throws Exception {
        byte[] photo = Files.readAllBytes(Path.of(System.getenv("SHELVED_COMPARE_PHOTO")));
        var split = ImageTiler.split(Images.decode(photo));
        List<byte[]> tiles = split.images().stream().map(ImageTiler::jpeg).toList();
        String models = System.getenv().getOrDefault("SHELVED_COMPARE_MODELS", "claude-opus-5-5,claude-sonnet-5-5,claude-haiku-5-5");
        for (String model : models.split(",")) {
            var ai = new ClaudeLibraryAi(new ShelvedProperties(Path.of("target"), "local",
                    new ShelvedProperties.Anthropic(System.getenv("ANTHROPIC_API_KEY"), model), 99, ""));
            long t0 = System.currentTimeMillis();
            LibraryAi.ShelfReading r;
            try {
                r = ai.readShelf(tiles, split.direction() == ImageTiler.Direction.TOP_TO_BOTTOM);
            } catch (RuntimeException e) {
                System.out.printf("%n=== %s FAILED: %s%n", model, e.getMessage());
                continue;
            }
            long ms = System.currentTimeMillis() - t0;
            var u = ai.lastUsage();
            double[] p = PRICE_PER_MTOK.getOrDefault(model, new double[]{0, 0});
            double cents = (u.inputTokens() * p[0] + u.outputTokens() * p[1]) / 10_000.0;
            long found = TRUTH.stream().filter(t -> r.books().stream().anyMatch(b -> Similarity.title(t, b.title(), null) >= 0.5)).count();
            List<String> extra = r.books().stream().map(LibraryAi.SpineReading::title)
                    .filter(title -> TRUTH.stream().noneMatch(t -> Similarity.title(t, title, null) >= 0.5)).toList();
            System.out.printf("%n=== %s: %d s, in %d / out %d tokens, %.1f ct, %d books listed, %d/42 found%n",
                    model, ms / 1000, u.inputTokens(), u.outputTokens(), cents, r.books().size(), found);
            System.out.println("not matched: " + extra);
            System.out.println("rows: " + r.books().stream().collect(java.util.stream.Collectors.groupingBy(
                    LibraryAi.SpineReading::row, java.util.TreeMap::new, java.util.stream.Collectors.counting())));
            r.boards().forEach(b -> System.out.printf("board %d: fill %.2f, both ends %s%n", b.row(), b.fill(), b.bothEndsVisible()));
        }
    }
}
