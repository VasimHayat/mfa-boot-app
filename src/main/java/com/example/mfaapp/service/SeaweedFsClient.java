package com.example.mfaapp.service;

import com.example.mfaapp.config.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.Optional;

/**
 * Talks to the SeaweedFS filer over its HTTP API.
 *
 * <p>The filer gives every object a path, so the app addresses files by a key it generates
 * ({@code /mfa-learning/documents/u42/<uuid>.pdf}) instead of tracking SeaweedFS file ids and doing
 * the master-assign/volume-write dance itself.
 *
 * <p>Keys are generated, never derived from user input, and are asserted to be URL-safe before use —
 * a key carrying {@code ../} or an encoded slash would otherwise let a caller address another user's
 * subtree.
 */
public class SeaweedFsClient {

    private static final Logger log = LoggerFactory.getLogger(SeaweedFsClient.class);

    /** Generated keys only ever contain these characters; anything else is a bug, not user input. */
    private static final String SAFE_KEY = "/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+";

    private final RestClient restClient;
    private final StorageProperties properties;

    public SeaweedFsClient(RestClient restClient, StorageProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * Writes (or overwrites) the object at {@code key}.
     *
     * <p>The body is assembled as a plain {@code MultiValueMap} rather than with
     * {@code MultipartBodyBuilder}, which would drag {@code reactive-streams} onto the classpath for
     * an async capability this call never uses.
     */
    public void store(String key, byte[] content, String contentType, String downloadName) {
        assertSafeKey(key);

        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new NamedByteArrayResource(content, downloadName), partHeaders));

        try {
            restClient.post()
                    .uri(absolute(key))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new StorageUnavailableException(
                                "SeaweedFS rejected the upload with HTTP " + response.getStatusCode());
                    })
                    .toBodilessEntity();
        } catch (StorageUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            throw new StorageUnavailableException("Could not reach the file store", e);
        }
    }

    /**
     * Reads the object, or empty if the filer no longer has it.
     *
     * <p>Uses {@code exchange} so a 404 is distinguished from a successful empty body by its status
     * rather than by whether the error response happened to carry any bytes.
     */
    public Optional<byte[]> read(String key) {
        assertSafeKey(key);
        try {
            return restClient.get()
                    .uri(absolute(key))
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.value() == HttpStatus.NOT_FOUND.value()) {
                            return Optional.<byte[]>empty();
                        }
                        if (status.isError()) {
                            throw new StorageUnavailableException("SeaweedFS returned HTTP " + status);
                        }
                        return Optional.of(response.getBody().readAllBytes());
                    }, false);
        } catch (StorageUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            throw new StorageUnavailableException("Could not reach the file store", e);
        }
    }

    /**
     * Removes the object. A missing object is treated as success, so deleting a record whose bytes
     * are already gone still cleans up the metadata.
     */
    public void delete(String key) {
        assertSafeKey(key);
        try {
            restClient.delete()
                    .uri(absolute(key))
                    .retrieve()
                    .onStatus(status -> status.isError() && status.value() != HttpStatus.NOT_FOUND.value(),
                            (request, response) -> {
                                throw new StorageUnavailableException(
                                        "SeaweedFS returned HTTP " + response.getStatusCode());
                            })
                    .toBodilessEntity();
        } catch (StorageUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            throw new StorageUnavailableException("Could not reach the file store", e);
        }
    }

    /** Whether the filer is answering right now. Used by the health indicator, never on the hot path. */
    public boolean isReachable() {
        try {
            restClient.get()
                    .uri(URI.create(properties.getFilerUrl() + "/"))
                    .retrieve()
                    .onStatus(status -> false, (request, response) -> {
                    })
                    .toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.debug("SeaweedFS filer unreachable at {}", properties.getFilerUrl(), e);
            return false;
        }
    }

    private URI absolute(String key) {
        // The key is already URL-safe, so this avoids the encoding a UriBuilder would apply to '/'.
        return URI.create(properties.getFilerUrl() + key);
    }

    private static void assertSafeKey(String key) {
        if (key == null || !key.matches(SAFE_KEY) || key.contains("..")) {
            throw new IllegalArgumentException("Refusing to use an unsafe storage key");
        }
    }

    /** Multipart needs a filename on the part for the filer to record a sensible name. */
    private static final class NamedByteArrayResource extends ByteArrayResource {

        private final String filename;

        private NamedByteArrayResource(byte[] content, String filename) {
            super(content);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
