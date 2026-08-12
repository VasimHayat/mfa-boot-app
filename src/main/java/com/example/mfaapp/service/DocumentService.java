package com.example.mfaapp.service;

import com.example.mfaapp.config.StorageProperties;
import com.example.mfaapp.domain.StoredDocument;
import com.example.mfaapp.domain.User;
import com.example.mfaapp.repo.StoredDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Uploads, lists, downloads and deletes a user's documents.
 *
 * <p>Two rules shape the whole class. Every lookup is scoped to the owner, so one user can never
 * reach another's file. And the content type is derived from the extension rather than trusted from
 * the upload, so a browser cannot get {@code text/html} stored and served back.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /**
     * The only types this app will store. Deliberately excludes anything a browser renders as
     * active content — {@code .html} and {@code .svg} are absent because a stored one, served from
     * this origin, would be a stored-XSS vector.
     */
    private static final Map<String, String> TYPES_BY_EXTENSION = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"),
            Map.entry("csv", "text/csv"),
            Map.entry("rtf", "application/rtf"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("odt", "application/vnd.oasis.opendocument.text"),
            Map.entry("ods", "application/vnd.oasis.opendocument.spreadsheet"),
            Map.entry("odp", "application/vnd.oasis.opendocument.presentation"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("zip", "application/zip"));

    private final StoredDocumentRepository documents;
    private final SeaweedFsClient storage;
    private final StorageProperties properties;
    private final Clock clock;

    public DocumentService(StoredDocumentRepository documents, SeaweedFsClient storage,
                           StorageProperties properties, Clock clock) {
        this.documents = documents;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<StoredDocument> list(User owner, Pageable pageable) {
        return documents.findByOwnerIdOrderByUploadedAtDesc(owner.getId(), pageable);
    }

    @Transactional(readOnly = true)
    public DocumentUsage usage(User owner) {
        return new DocumentUsage(
                documents.countByOwnerId(owner.getId()),
                documents.sumSizeBytesByOwnerId(owner.getId()),
                properties.getMaxFilesPerUser(),
                properties.getMaxBytesPerUser().toBytes(),
                properties.getMaxFileSize().toBytes(),
                properties.getAllowedExtensions());
    }

    /**
     * Validates and stores an upload.
     *
     * <p>The bytes go to SeaweedFS first and the row second: a write that succeeds in the store but
     * fails in the database is rolled back by deleting the object, so the two never disagree in the
     * direction that would leak storage.
     */
    @Transactional
    public StoredDocument upload(User owner, String rawFilename, String declaredContentType, byte[] content) {
        if (content == null || content.length == 0) {
            throw new InvalidRequestException("The file is empty");
        }
        long maxFileBytes = properties.getMaxFileSize().toBytes();
        if (content.length > maxFileBytes) {
            throw new InvalidRequestException(
                    "That file is larger than the " + describeBytes(maxFileBytes) + " limit");
        }

        String filename = sanitizeFilename(rawFilename);
        String extension = extensionOf(filename);
        if (extension.isEmpty() || !properties.getAllowedExtensions().contains(extension)) {
            throw new InvalidRequestException(
                    "Files of that type cannot be uploaded. Allowed: " + String.join(", ",
                            properties.getAllowedExtensions().stream().sorted().toList()));
        }
        // Derived, not `declaredContentType` — the browser's claim is only a hint and is not trusted.
        String contentType = TYPES_BY_EXTENSION.getOrDefault(extension, "application/octet-stream");

        enforceQuota(owner, content.length);

        String checksum = sha256Hex(content);
        String storageKey = "%s/u%d/%s.%s".formatted(
                trimTrailingSlash(properties.getBasePath()), owner.getId(), UUID.randomUUID(), extension);

        storage.store(storageKey, content, contentType, filename);
        try {
            StoredDocument document = new StoredDocument(owner, filename, contentType, content.length,
                    checksum, storageKey, Instant.now(clock));
            return documents.saveAndFlush(document);
        } catch (RuntimeException e) {
            // Do not leave bytes behind that nothing points at.
            try {
                storage.delete(storageKey);
            } catch (RuntimeException cleanupFailure) {
                log.warn("Orphaned object {} after a failed metadata write", storageKey, cleanupFailure);
            }
            throw e;
        }
    }

    /** Metadata plus bytes, or empty if this user has no such document. */
    @Transactional(readOnly = true)
    public Optional<DocumentContent> download(User owner, Long documentId) {
        return documents.findByIdAndOwnerId(documentId, owner.getId())
                .map(document -> {
                    byte[] content = storage.read(document.getStorageKey())
                            .orElseThrow(() -> new StorageUnavailableException(
                                    "The stored file is missing from the object store"));
                    return new DocumentContent(document, content);
                });
    }

    /** Removes the row and the object. Returns false if this user has no such document. */
    @Transactional
    public boolean delete(User owner, Long documentId) {
        Optional<StoredDocument> found = documents.findByIdAndOwnerId(documentId, owner.getId());
        if (found.isEmpty()) {
            return false;
        }
        StoredDocument document = found.get();
        // Metadata first: if the object delete then fails, the user still sees the file gone and the
        // orphan is recoverable from logs, which is better than a row pointing at nothing.
        documents.delete(document);
        documents.flush();
        storage.delete(document.getStorageKey());
        return true;
    }

    private void enforceQuota(User owner, long incomingBytes) {
        long count = documents.countByOwnerId(owner.getId());
        if (count >= properties.getMaxFilesPerUser()) {
            throw new InvalidRequestException(
                    "You have reached the limit of " + properties.getMaxFilesPerUser() + " files");
        }
        long used = documents.sumSizeBytesByOwnerId(owner.getId());
        long allowed = properties.getMaxBytesPerUser().toBytes();
        if (used + incomingBytes > allowed) {
            throw new InvalidRequestException(
                    "That upload would exceed your " + describeBytes(allowed) + " of storage");
        }
    }

    /**
     * Reduces whatever the browser sent to a plain filename. Browsers have historically sent full
     * paths, and the name is echoed back in a download header, so path separators and control
     * characters both have to go.
     */
    static String sanitizeFilename(String raw) {
        String name = raw == null ? "" : raw;
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        name = name.replaceAll("\\p{Cntrl}", "")
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            name = "upload";
        }
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    /** Lower-case extension without the dot, or empty when there is not a usable one. */
    static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.matches("[a-z0-9]{1,10}") ? extension : "";
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JRE", e);
        }
    }

    private static String trimTrailingSlash(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static String describeBytes(long bytes) {
        if (bytes >= 1024 * 1024) {
            return (bytes / (1024 * 1024)) + " MB";
        }
        return (bytes / 1024) + " KB";
    }

    public record DocumentContent(StoredDocument document, byte[] content) {
    }

    public record DocumentUsage(long fileCount, long bytesUsed, int maxFiles, long maxBytes,
                                long maxFileSizeBytes, java.util.Set<String> allowedExtensions) {
    }
}
