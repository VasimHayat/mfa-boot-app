package com.example.mfaapp.support;

import com.example.mfaapp.config.StorageProperties;
import com.example.mfaapp.service.SeaweedFsClient;
import com.example.mfaapp.service.StorageUnavailableException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stands in for SeaweedFS so the suite does not need Docker running.
 *
 * <p>It keeps the real {@link SeaweedFsClient} contract — same key validation, same "missing object
 * is empty, unreachable store throws" semantics — so the service code under test takes exactly the
 * paths it takes in production. The real client is exercised separately by
 * {@code SeaweedFsClientIT}, which only runs when a filer is actually up.
 */
public class InMemoryObjectStore extends SeaweedFsClient {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private volatile boolean available = true;

    public InMemoryObjectStore(StorageProperties properties) {
        super(null, properties);
    }

    @Override
    public void store(String key, byte[] content, String contentType, String downloadName) {
        requireAvailable();
        assertKeyLooksGenerated(key);
        objects.put(key, content.clone());
    }

    @Override
    public Optional<byte[]> read(String key) {
        requireAvailable();
        assertKeyLooksGenerated(key);
        return Optional.ofNullable(objects.get(key)).map(byte[]::clone);
    }

    @Override
    public void delete(String key) {
        requireAvailable();
        assertKeyLooksGenerated(key);
        objects.remove(key);
    }

    @Override
    public boolean isReachable() {
        return available;
    }

    // --- test controls ---------------------------------------------------------------------

    /** Simulates the filer being down. */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    public int size() {
        return objects.size();
    }

    public boolean contains(String key) {
        return objects.containsKey(key);
    }

    public void clear() {
        objects.clear();
        available = true;
    }

    private void requireAvailable() {
        if (!available) {
            throw new StorageUnavailableException("Object store is offline (test)");
        }
    }

    /** Mirrors the real client's refusal to touch anything that is not a generated, safe key. */
    private static void assertKeyLooksGenerated(String key) {
        if (key == null || !key.matches("/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+") || key.contains("..")) {
            throw new IllegalArgumentException("Refusing to use an unsafe storage key: " + key);
        }
    }
}
