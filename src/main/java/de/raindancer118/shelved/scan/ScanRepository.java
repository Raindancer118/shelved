package de.raindancer118.shelved.scan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ScanRepository extends JpaRepository<Scan, UUID> {
    Optional<Scan> findByIdAndOwnerId(UUID id, String ownerId);

    java.util.List<Scan> findByStatus(Scan.Status status);

    java.util.List<Scan> findByOwnerIdAndModeAndStatusAndPhotoFileNotNullOrderByCreatedAtDesc(String ownerId, Scan.Mode mode, Scan.Status status);
}
