package de.raindancer118.shelved.book;

import java.util.List;

public final class Titles {

    // MARC21 non-sorting markers as delivered by the DNB (&#152; … &#156;)
    private static final char NSB = '\u0098';
    private static final char NSE = '\u009C';
    private static final List<String> ARTICLES = List.of("Der ", "Die ", "Das ", "Ein ", "Eine ", "The ", "A ", "An ");

    private Titles() {
    }

    public static String display(String title) {
        if (title == null) {
            return null;
        }
        return title.replace(String.valueOf(NSB), "").replace(String.valueOf(NSE), "").trim();
    }

    public static String sortForm(String title) {
        if (title == null) {
            return "";
        }
        int start = title.indexOf(NSB);
        int end = title.indexOf(NSE);
        if (start >= 0 && end > start) {
            return display(title.substring(0, start) + title.substring(end + 1));
        }
        String t = display(title);
        for (String article : ARTICLES) {
            if (t.startsWith(article) && t.length() > article.length()) {
                return t.substring(article.length()).trim();
            }
        }
        return t;
    }

    private static final java.util.regex.Pattern BRACKET_LETTERS = java.util.regex.Pattern.compile("(\\p{L})\\((\\p{L}+)\\)");

    /** "Bis(s) zum Abendrot" → "Biss zum Abendrot": letters that a bracket only sets apart belong to the word. */
    public static String joinBracketLetters(String s) {
        return s == null ? null : BRACKET_LETTERS.matcher(s).replaceAll("$1$2");
    }
}
