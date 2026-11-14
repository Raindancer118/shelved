package de.raindancer118.shelved.series;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SeriesCatalogRepository extends JpaRepository<SeriesCatalogEntry, String> {
}
