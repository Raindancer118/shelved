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
        Set<String> x = tokens(a);
        Set<String> y = tokens(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return (double) inter.size() / union.size();
    }

    public static boolean surname(String a, String b) {
        return surnameKey(a).equals(surnameKey(b)) && !surnameKey(a).isEmpty();
    }

    static String surnameKey(String name) {
        String sort = Names.sortForm(name);
        int comma = sort.indexOf(',');
        return Names.collationKey(comma > 0 ? sort.substring(0, comma) : sort);
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
