package com.example.mfaapp.support;

import com.example.mfaapp.config.StorageProperties;
import com.example.mfaapp.service.SeaweedFsClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Replaces the application clock with one the tests control. {@code @Primary} makes the choice
 * deterministic regardless of the order in which configuration classes are processed.
 */
@TestConfiguration
public class TestClockConfig {

    /** Arbitrary but fixed: mid-window of a 30-second TOTP step, well away from a boundary. */
    public static final Instant START = Instant.parse("2026-03-01T12:00:15Z");

    @Bean
    @Primary
    public Clock clock() {
        return new MutableClock(START, ZoneOffset.UTC);
    }

    /**
     * Keeps the suite runnable without Docker. {@code SeaweedFsClientIT} covers the real filer and
     * skips itself when one is not listening.
     */
    @Bean
    @Primary
    public SeaweedFsClient seaweedFsClient(StorageProperties storageProperties) {
        return new InMemoryObjectStore(storageProperties);
    }
}
