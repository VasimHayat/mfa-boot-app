package com.example.mfaapp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Metadata for one file a user uploaded. The bytes live in SeaweedFS; this row is the only thing
 * that says who owns them and what they were called.
 */
@Entity
@Table(name = "stored_document",
        uniqueConstraints = @UniqueConstraint(name = "uk_stored_document_key", columnNames = "storage_key"),
        indexes = @Index(name = "ix_stored_document_owner", columnList = "owner_id, uploaded_at"))
public class StoredDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, foreignKey = @jakarta.persistence.ForeignKey(name = "fk_document_owner"))
    private User owner;

    /** The user's filename, sanitised. Only ever used as a download name, never as a path. */
    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    /** Derived from the extension by the server, not taken from the browser's claim. */
    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Hex SHA-256 of the stored bytes, so a download can be checked against what was written. */
    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    /** Full filer path. Generated, opaque to the user, and never echoed to the client. */
    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected StoredDocument() {
        // for JPA
    }

    public StoredDocument(User owner, String filename, String contentType, long sizeBytes,
                          String checksumSha256, String storageKey, Instant uploadedAt) {
        this.owner = owner;
        this.filename = filename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksumSha256 = checksumSha256;
        this.storageKey = storageKey;
        this.uploadedAt = uploadedAt;
    }

    public Long getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
