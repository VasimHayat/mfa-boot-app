package com.example.mfaapp.repo;

import com.example.mfaapp.domain.StoredDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StoredDocumentRepository extends JpaRepository<StoredDocument, Long> {

    Page<StoredDocument> findByOwnerIdOrderByUploadedAtDesc(Long ownerId, Pageable pageable);

    /**
     * Ownership is part of the lookup rather than a check after loading, so a document belonging to
     * someone else is indistinguishable from one that does not exist.
     */
    Optional<StoredDocument> findByIdAndOwnerId(Long id, Long ownerId);

    long countByOwnerId(Long ownerId);

    @Query("select coalesce(sum(d.sizeBytes), 0) from StoredDocument d where d.owner.id = :ownerId")
    long sumSizeBytesByOwnerId(@Param("ownerId") Long ownerId);
}
