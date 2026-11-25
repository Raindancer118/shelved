package de.raindancer118.shelved.metadata;

import de.raindancer118.shelved.book.Names;
import de.raindancer118.shelved.book.Titles;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public final class Similarity {

    private Similarity() {
    }

    /** Token overlap of the sortable titles, 0..1. Robust against articles, case, umlauts and punctuation. */
    public static double title(String a, String b) {
        return jaccard(tokens(a), tokens(b));
    }

    /**
     * What a spine says vs. what a catalogue has: spines often print "Title – Subtitle", catalogues split them,
     * and catalogues sometimes carry long additions ("Die Queen und ich – aus dem Leben eines königlichen Corgis").
     */
    public static double title(String spine, String catalogueTitle, String catalogueSubtitle) {
        Set<String> s = tokens(spine);
        Set<String> t = tokens(catalogueTitle);
        Set<String> full = new HashSet<>(t);
        full.addAll(tokens(catalogueSubtitle));
        double best = Math.max(jaccard(s, t), jaccard(s, full));
        if (s.size() >= 2 && t.containsAll(s) || t.size() >= 2 && s.containsAll(t)) {
            best = Math.max(best, 0.8);
        }
        return best;
    }

    /** Spine text vs. an edition, its subtitle included. */
    public static double edition(String spine, BookMetadata m) {
        return title(spine, m.title(), m.subtitle());
    }

    public static String mainTitle(String title) {
        if (title == null) {
            return null;
        }
        String[] parts = title.split("\\s+[–—-]\\s+|:\\s+", 2);
        return parts[0].trim();
    }

    public static boolean surname(String a, String b) {
        return surnameKey(a).equals(surnameKey(b)) && !surnameKey(a).isEmpty();
    }

    static String surnameKey(String name) {
        String sort = Names.sortForm(name);
        int comma = sort.indexOf(',');
        return Names.collationKey(comma > 0 ? sort.substring(0, comma) : sort);
    }

    private static double jaccard(Set<String> x, Set<String> y) {
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return (double) inter.size() / union.size();
    }

    private static Set<String> tokens(String s) {
        if (s == null) {
            return Set.of();
        }
        return Arrays.stream(Names.collationKey(Titles.sortForm(s)).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> !t.isBlank())
                .collect(Collectors.toSet());
    }
}
