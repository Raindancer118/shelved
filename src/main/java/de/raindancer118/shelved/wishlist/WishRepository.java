package de.raindancer118.shelved.wishlist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WishRepository extends JpaRepository<Wish, UUID> {

    List<Wish> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

    Optional<Wish> findByIdAndOwnerId(UUID id, String ownerId);
}
