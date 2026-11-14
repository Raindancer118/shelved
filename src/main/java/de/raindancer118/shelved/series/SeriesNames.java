package de.raindancer118.shelved.series;

import de.raindancer118.shelved.book.Titles;

import java.util.regex.Pattern;

/** One name per series, whichever source said it: "Eragon-Tetralogie", "Eragon" and "Eragon Saga" are the same. */
public final class SeriesNames {

    private static final Pattern SUFFIX = Pattern.compile(
            "(?i)[\\s-]+(reihe|romanreihe|buchreihe|romane|serie|series|trilogie|tetralogie|pentalogie|dilogie|duologie|"
                    + "zyklus|saga|trilogy|duology|cycle|novels)$");
    private static final Pattern BRACKET = Pattern.compile("\\s*\\((roman|buch)?reihe\\)$", Pattern.CASE_INSENSITIVE);

    private SeriesNames() {
    }

    public static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String n = Titles.display(name).trim();
        n = BRACKET.matcher(n).replaceAll("");
        String before;
        do {
            before = n;
            n = SUFFIX.matcher(n).replaceAll("").trim();
        } while (!n.equals(before) && !n.isBlank());
        return n.isBlank() ? Titles.display(name).trim() : n;
    }
}
