package de.raindancer118.shelved.book;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Author names: "Terry Pratchett" ⇄ "Pratchett, Terry", the way library catalogues sort them. */
public final class Names {

    private static final Set<String> LOWER_PARTICLES = Set.of("von", "van", "de", "der", "den", "zu", "da", "di", "du", "la", "le", "vom", "zum", "ten", "ter");
    private static final Set<String> CAPITAL_PREFIXES = Set.of("Le", "La", "De", "Du", "Di", "Da", "Van", "Von", "Mac", "O'", "St.");

    private Names() {
    }

    public static String sortForm(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String n = name.trim().replaceAll("\\s+", " ");
        if (n.contains(",")) {
            return n;
        }
        List<String> parts = Arrays.asList(n.split(" "));
        if (parts.size() == 1) {
            return n;
        }
        int surnameStart = parts.size() - 1;
        while (surnameStart > 1 && CAPITAL_PREFIXES.contains(parts.get(surnameStart - 1))) {
            surnameStart--;
        }
        int particleStart = surnameStart;
        while (particleStart > 1 && LOWER_PARTICLES.contains(parts.get(particleStart - 1))) {
            particleStart--;
        }
        String surname = String.join(" ", parts.subList(surnameStart, parts.size()));
        String given = String.join(" ", parts.subList(0, particleStart));
        String particles = String.join(" ", parts.subList(particleStart, surnameStart));
        return surname + ", " + given + (particles.isEmpty() ? "" : " " + particles);
    }

    public static String displayForm(String name) {
        if (name == null) {
            return "";
        }
        int comma = name.indexOf(',');
        if (comma < 0) {
            return name.trim();
        }
        String surname = name.substring(0, comma).trim();
        String rest = name.substring(comma + 1).trim();
        return rest.isEmpty() ? surname : rest + " " + surname;
    }

    /** Case- and accent-insensitive key, so "Ärger" sorts with "Arger" like in a German bookshop. */
    public static String collationKey(String s) {
        if (s == null) {
            return "";
        }
        String folded = s.replace("ß", "ss");
        return Normalizer.normalize(folded, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}
