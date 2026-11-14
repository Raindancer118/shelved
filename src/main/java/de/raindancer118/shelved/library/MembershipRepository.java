package de.raindancer118.shelved.library;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
    List<Membership> findByAccountIdOrderByCreatedAt(UUID accountId);

    List<Membership> findByLibraryIdOrderByCreatedAt(UUID libraryId);

    Optional<Membership> findByLibraryIdAndAccountId(UUID libraryId, UUID accountId);
}
