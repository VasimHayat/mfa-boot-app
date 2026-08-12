package com.example.mfaapp.web.dto;

import com.example.mfaapp.domain.StoredDocument;
import com.example.mfaapp.service.DocumentService;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Wire shapes for the document endpoints. The storage key is never exposed. */
public final class DocumentDtos {

    private DocumentDtos() {
    }

    public record DocumentDto(Long id, String filename, String contentType, long sizeBytes,
                              String checksumSha256, Instant uploadedAt) {

        public static DocumentDto from(StoredDocument document) {
            return new DocumentDto(document.getId(), document.getFilename(), document.getContentType(),
                    document.getSizeBytes(), document.getChecksumSha256(), document.getUploadedAt());
        }
    }

    /** What the upload UI needs to show limits before the user picks a file. */
    public record StorageUsageDto(long fileCount, long bytesUsed, int maxFiles, long maxBytes,
                                  long maxFileSizeBytes, List<String> allowedExtensions) {

        public static StorageUsageDto from(DocumentService.DocumentUsage usage) {
            Set<String> extensions = usage.allowedExtensions();
            return new StorageUsageDto(usage.fileCount(), usage.bytesUsed(), usage.maxFiles(),
                    usage.maxBytes(), usage.maxFileSizeBytes(), extensions.stream().sorted().toList());
        }
    }
}
