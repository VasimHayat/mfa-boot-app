package com.example.mfaapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Set;

/** Settings for the SeaweedFS-backed document store ({@code app.storage.*}). */
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** Base URL of the SeaweedFS filer, which exposes the path-based HTTP API. */
    private String filerUrl = "http://localhost:8888";

    /** Prefix every object is written under, so the app owns a subtree of the filer namespace. */
    private String basePath = "/mfa-learning/documents";

    private DataSize maxFileSize = DataSize.ofMegabytes(10);

    /** Per-user ceilings, checked before anything is written. */
    private int maxFilesPerUser = 100;
    private DataSize maxBytesPerUser = DataSize.ofMegabytes(200);

    /**
     * Extensions a user may upload. The stored content type is derived from the extension rather
     * than taken from the browser's claim, so this list is what actually decides what can be stored.
     * Executables and inline-renderable formats (html, svg) are deliberately absent.
     */
    private Set<String> allowedExtensions = new LinkedHashSet<>(Set.of(
            "pdf", "txt", "md", "csv", "rtf", "json", "xml",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "odt", "ods", "odp",
            "png", "jpg", "jpeg", "gif", "webp",
            "zip"));

    @DurationUnit(ChronoUnit.MILLIS)
    private Duration connectTimeout = Duration.ofSeconds(3);

    @DurationUnit(ChronoUnit.MILLIS)
    private Duration readTimeout = Duration.ofSeconds(30);

    public String getFilerUrl() {
        return filerUrl;
    }

    public void setFilerUrl(String filerUrl) {
        this.filerUrl = filerUrl;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public int getMaxFilesPerUser() {
        return maxFilesPerUser;
    }

    public void setMaxFilesPerUser(int maxFilesPerUser) {
        this.maxFilesPerUser = maxFilesPerUser;
    }

    public DataSize getMaxBytesPerUser() {
        return maxBytesPerUser;
    }

    public void setMaxBytesPerUser(DataSize maxBytesPerUser) {
        this.maxBytesPerUser = maxBytesPerUser;
    }

    public Set<String> getAllowedExtensions() {
        return allowedExtensions;
    }

    public void setAllowedExtensions(Set<String> allowedExtensions) {
        this.allowedExtensions = new LinkedHashSet<>(allowedExtensions);
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
