package de.raindancer118.shelved.series;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class SeriesGaps {

    public record Missing(int number, String title) {
    }

    private SeriesGaps() {
    }

    /**
     * Without a catalog only holes below the highest owned volume are known.
     * With one, everything in the catalog that isn't owned is missing.
     */
    public static List<Missing> missing(Set<Integer> owned, Collection<SeriesCatalogVolume> catalog) {
        Map<Integer, String> titles = catalog.stream()
                .collect(Collectors.toMap(SeriesCatalogVolume::number, SeriesCatalogVolume::title, (a, b) -> a, TreeMap::new));
        TreeMap<Integer, Missing> result = new TreeMap<>();
        if (!owned.isEmpty()) {
            int max = owned.stream().mapToInt(Integer::intValue).max().orElse(0);
            for (int n = 1; n < max; n++) {
                if (!owned.contains(n)) {
                    result.put(n, new Missing(n, titles.get(n)));
                }
            }
        }
        titles.forEach((n, t) -> {
            if (!owned.contains(n)) {
                result.put(n, new Missing(n, t));
            }
        });
        return List.copyOf(result.values());
    }

}
