package de.raindancer118.shelved.shelf;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShelfRepository extends JpaRepository<Shelf, UUID> {

    List<Shelf> findByOwnerIdOrderByPosition(String ownerId);

    Optional<Shelf> findByIdAndOwnerId(UUID id, String ownerId);
}
