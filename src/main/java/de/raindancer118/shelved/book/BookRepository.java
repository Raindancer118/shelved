package de.raindancer118.shelved.book;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookRepository extends JpaRepository<Book, UUID> {

    List<Book> findByOwnerId(String ownerId);

    Optional<Book> findByIdAndOwnerId(UUID id, String ownerId);

    List<Book> findByOwnerIdAndIsbn13(String ownerId, String isbn13);

    long countByOwnerId(String ownerId);
}
