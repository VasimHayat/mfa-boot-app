package com.example.mfaapp.web;

import com.example.mfaapp.domain.StoredDocument;
import com.example.mfaapp.domain.User;
import com.example.mfaapp.service.DocumentService;
import com.example.mfaapp.service.ResourceNotFoundException;
import com.example.mfaapp.service.UserService;
import com.example.mfaapp.web.dto.DocumentDtos.DocumentDto;
import com.example.mfaapp.web.dto.DocumentDtos.StorageUsageDto;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A user's own documents, stored in SeaweedFS. Requires a session that has cleared MFA; every
 * operation is scoped to the caller, so ids belonging to other users read as "not found".
 */
@RestController
@RequestMapping("/api/me/documents")
public class DocumentController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final DocumentService documentService;
    private final UserService userService;

    public DocumentController(DocumentService documentService, UserService userService) {
        this.documentService = documentService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<Page<DocumentDto>> list(Authentication authentication,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        User user = userService.require(authentication.getName());
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        return ResponseEntity.ok(documentService.list(user, PageRequest.of(safePage, safeSize))
                .map(DocumentDto::from));
    }

    /** Counts and limits, so the UI can show remaining quota without fetching every row. */
    @GetMapping("/usage")
    public ResponseEntity<StorageUsageDto> usage(Authentication authentication) {
        User user = userService.require(authentication.getName());
        return ResponseEntity.ok(StorageUsageDto.from(documentService.usage(user)));
    }

    @PostMapping
    public ResponseEntity<DocumentDto> upload(Authentication authentication,
                                              @RequestParam("file") MultipartFile file) throws IOException {
        User user = userService.require(authentication.getName());
        StoredDocument stored = documentService.upload(user, file.getOriginalFilename(),
                file.getContentType(), file.getBytes());
        return ResponseEntity.ok(DocumentDto.from(stored));
    }

    /**
     * Always an attachment, never inline: combined with the server-derived content type and the
     * global {@code nosniff} header, an uploaded file cannot be coaxed into executing on this origin.
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(Authentication authentication, @PathVariable Long id) {
        User user = userService.require(authentication.getName());
        DocumentService.DocumentContent content = documentService.download(user, id)
                .orElseThrow(() -> new ResourceNotFoundException("Document " + id));
        StoredDocument document = content.document();

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(document.getFilename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(document.getContentType()))
                .contentLength(document.getSizeBytes())
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ByteArrayResource(content.content()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable Long id) {
        User user = userService.require(authentication.getName());
        if (!documentService.delete(user, id)) {
            throw new ResourceNotFoundException("Document " + id);
        }
        return ResponseEntity.noContent().build();
    }
}
