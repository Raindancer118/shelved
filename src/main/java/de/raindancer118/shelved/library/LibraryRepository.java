package de.raindancer118.shelved.library;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LibraryRepository extends JpaRepository<Library, UUID> {
}
