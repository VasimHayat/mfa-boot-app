package com.example.mfaapp;

import com.example.mfaapp.config.StorageProperties;
import com.example.mfaapp.service.SeaweedFsClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises {@link SeaweedFsClient} against a real filer.
 *
 * <p>Skips itself when nothing is listening, so `mvn test` passes without Docker. To run it for
 * real: {@code docker compose up -d}, then {@code mvn test -Dtest=SeaweedFsClientIT}.
 *
 * <p>The rest of the suite uses an in-memory stand-in; this is the test that proves the HTTP calls
 * against SeaweedFS are actually right.
 */
class SeaweedFsClientIT {

    private static final String FILER_URL = System.getProperty("seaweedfs.url", "http://localhost:8888");

    private static SeaweedFsClient client;
    private static StorageProperties properties;

    @BeforeAll
    static void setUp() {
        assumeTrue(filerIsListening(), "SeaweedFS filer is not running at " + FILER_URL);

        properties = new StorageProperties();
        properties.setFilerUrl(FILER_URL);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        client = new SeaweedFsClient(RestClient.builder().requestFactory(requestFactory).build(), properties);
    }

    @Test
    @DisplayName("Store, read back, then delete against a live filer")
    void roundTripsAgainstTheRealFiler() {
        String key = "/mfa-learning/it/" + UUID.randomUUID() + ".txt";
        byte[] content = "seaweedfs round trip".getBytes(StandardCharsets.UTF_8);

        client.store(key, content, "text/plain", "round-trip.txt");

        assertThat(client.read(key)).hasValueSatisfying(read -> assertThat(read).isEqualTo(content));

        client.delete(key);
        assertThat(client.read(key))
                .as("a deleted object must read back as absent, not as empty bytes")
                .isEmpty();
    }

    @Test
    @DisplayName("Binary content survives the round trip byte for byte")
    void binaryContentIsUnchanged() {
        String key = "/mfa-learning/it/" + UUID.randomUUID() + ".zip";
        byte[] content = new byte[4096];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 256);
        }

        client.store(key, content, "application/zip", "archive.zip");
        try {
            assertThat(client.read(key)).hasValueSatisfying(read -> assertThat(read).isEqualTo(content));
        } finally {
            client.delete(key);
        }
    }

    @Test
    @DisplayName("Reading an object that was never written is empty, not an error")
    void missingObjectReadsAsEmpty() {
        assertThat(client.read("/mfa-learning/it/" + UUID.randomUUID() + ".txt")).isEmpty();
    }

    @Test
    @DisplayName("Deleting something that is not there is not an error")
    void deletingAMissingObjectSucceeds() {
        client.delete("/mfa-learning/it/" + UUID.randomUUID() + ".txt");
    }

    @Test
    @DisplayName("A key that could escape its subtree is refused before any request is made")
    void unsafeKeysAreRefused() {
        for (String key : new String[]{"/a/../../etc/passwd", "relative/path.txt", "/a b/c.txt", "/a%2f..%2fb"}) {
            assertThatThrownBy(() -> client.read(key))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsafe storage key");
        }
    }

    @Test
    @DisplayName("The filer reports itself reachable")
    void reachabilityIsDetected() {
        assertThat(client.isReachable()).isTrue();
    }

    private static boolean filerIsListening() {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(FILER_URL + "/").toURL().openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setRequestMethod("GET");
            connection.getResponseCode();
            connection.disconnect();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
