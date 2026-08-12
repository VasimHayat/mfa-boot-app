package com.example.mfaapp;

import com.example.mfaapp.domain.Role;
import com.example.mfaapp.domain.StoredDocument;
import com.example.mfaapp.domain.User;
import com.example.mfaapp.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithMockUser(username = DocumentUploadTest.OWNER, roles = "USER")
class DocumentUploadTest extends IntegrationTestBase {

    static final String OWNER = "owner";
    static final String OTHER = "someone-else";

    private User owner;

    @BeforeEach
    void seedUsers() {
        owner = newUser(OWNER, Role.USER);
        newUser(OTHER, Role.USER);
    }

    @Test
    @DisplayName("A document round-trips through the object store with its bytes intact")
    void uploadThenDownloadReturnsTheSameBytes() throws Exception {
        byte[] bytes = "the quick brown fox".getBytes(StandardCharsets.UTF_8);

        MvcResult upload = mockMvc.perform(multipart("/api/me/documents")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", bytes))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("notes.txt"))
                .andExpect(jsonPath("$.contentType").value("text/plain"))
                .andExpect(jsonPath("$.sizeBytes").value(bytes.length))
                .andExpect(jsonPath("$.checksumSha256").isNotEmpty())
                // The filer path is an internal detail and must never reach the client.
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andReturn();

        long id = objectMapper.readTree(upload.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/me/documents/" + id + "/download"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                // Always an attachment: an uploaded file must never render on this origin.
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("notes.txt")));

        StoredDocument stored = storedDocuments.findAll().get(0);
        assertThat(objectStore().contains(stored.getStorageKey())).isTrue();
        assertThat(stored.getStorageKey())
                .as("keys are generated per owner and never contain the user's filename")
                .startsWith("/mfa-learning/documents/u" + owner.getId() + "/")
                .endsWith(".txt")
                .doesNotContain("notes");
    }

    @Test
    @DisplayName("One user cannot see, download or delete another user's document")
    void documentsAreScopedToTheirOwner() throws Exception {
        long otherUsersDocument = uploadAs(OTHER, "private.txt", "secret".getBytes(StandardCharsets.UTF_8));

        // Not in the list...
        mockMvc.perform(get("/api/me/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // ...and addressing it directly is a 404, not a 403, so it does not confirm the id exists.
        mockMvc.perform(get("/api/me/documents/" + otherUsersDocument + "/download"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));

        mockMvc.perform(delete("/api/me/documents/" + otherUsersDocument).with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(storedDocuments.count())
                .as("the other user's document must survive the delete attempt")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Disallowed file types are refused and nothing is written to the store")
    void disallowedTypesAreRejected() throws Exception {
        for (String filename : new String[]{"payload.html", "icon.svg", "run.exe", "script.js", "noextension"}) {
            mockMvc.perform(multipart("/api/me/documents")
                            .file(new MockMultipartFile("file", filename, "text/plain",
                                    "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)))
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"));
        }

        assertThat(storedDocuments.count()).isZero();
        assertThat(objectStore().size())
                .as("a rejected upload must not leave bytes behind")
                .isZero();
    }

    @Test
    @DisplayName("The stored content type comes from the extension, not the browser's claim")
    void contentTypeIsDerivedNotTrusted() throws Exception {
        mockMvc.perform(multipart("/api/me/documents")
                        // A browser claiming text/html for a .txt file must not get text/html stored.
                        .file(new MockMultipartFile("file", "report.txt", "text/html",
                                "plain".getBytes(StandardCharsets.UTF_8)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value("text/plain"));
    }

    @Test
    @DisplayName("A filename carrying a path or traversal is reduced to a plain name")
    void filenamesAreSanitised() throws Exception {
        mockMvc.perform(multipart("/api/me/documents")
                        .file(new MockMultipartFile("file", "../../../etc/passwd.txt", "text/plain",
                                "x".getBytes(StandardCharsets.UTF_8)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("passwd.txt"));

        mockMvc.perform(multipart("/api/me/documents")
                        .file(new MockMultipartFile("file", "C:\\Users\\bob\\my report.pdf", "application/pdf",
                                "x".getBytes(StandardCharsets.UTF_8)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("my report.pdf"));

        assertThat(storedDocuments.findAll())
                .allSatisfy(document -> assertThat(document.getStorageKey())
                        .doesNotContain("..")
                        .doesNotContain(" "));
    }

    @Test
    @DisplayName("An empty file and an oversized file are both refused")
    void sizeBoundsAreEnforced() throws Exception {
        mockMvc.perform(multipart("/api/me/documents")
                        .file(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The file is empty"));

        // app.storage.max-file-size is 1KB under the test profile.
        mockMvc.perform(multipart("/api/me/documents")
                        .file(new MockMultipartFile("file", "big.txt", "text/plain", new byte[2048]))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("larger than")));

        assertThat(objectStore().size()).isZero();
    }

    @Test
    @DisplayName("Deleting removes both the metadata row and the stored object")
    void deleteRemovesRowAndObject() throws Exception {
        long id = uploadAs(OWNER, "temp.txt", "bye".getBytes(StandardCharsets.UTF_8));
        String key = storedDocuments.findAll().get(0).getStorageKey();
        assertThat(objectStore().contains(key)).isTrue();

        mockMvc.perform(delete("/api/me/documents/" + id).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(storedDocuments.count()).isZero();
        assertThat(objectStore().contains(key)).isFalse();
    }

    @Test
    @DisplayName("Usage reports the caller's own totals and the configured limits")
    void usageReflectsTheCallersOwnFiles() throws Exception {
        uploadAs(OWNER, "a.txt", new byte[100]);
        uploadAs(OWNER, "b.txt", new byte[50]);
        uploadAs(OTHER, "not-mine.txt", new byte[999]);

        mockMvc.perform(get("/api/me/documents/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileCount").value(2))
                .andExpect(jsonPath("$.bytesUsed").value(150))
                .andExpect(jsonPath("$.maxFiles").value(3))
                .andExpect(jsonPath("$.allowedExtensions").isArray());
    }

    @Test
    @DisplayName("The per-user file limit is enforced")
    void perUserFileLimitIsEnforced() throws Exception {
        // app.storage.max-files-per-user is 3 under the test profile.
        for (int i = 1; i <= 3; i++) {
            uploadAs(OWNER, "file" + i + ".txt", new byte[10]);
        }

        mockMvc.perform(multipart("/api/me/documents")
                        .file(new MockMultipartFile("file", "one-too-many.txt", "text/plain", new byte[10]))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("limit of 3 files")));

        assertThat(storedDocuments.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("When the object store is down, uploads fail with 503 and leave no orphan row")
    void storeOutageIsReportedAsUnavailable() throws Exception {
        objectStore().setAvailable(false);

        mockMvc.perform(multipart("/api/me/documents")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain",
                                "hello".getBytes(StandardCharsets.UTF_8)))
                        .with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("storage_unavailable"));

        assertThat(storedDocuments.count())
                .as("no metadata may be written if the bytes were never stored")
                .isZero();
    }

    @Test
    @DisplayName("Uploading requires a CSRF token and a fully authenticated session")
    void uploadsAreProtected() throws Exception {
        mockMvc.perform(multipart("/api/me/documents")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", new byte[10])))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("invalid_csrf_token"));
    }

    @Test
    @WithMockUser(username = "nobody", roles = "USER")
    @DisplayName("Listing is empty for a user who has uploaded nothing")
    void listingIsEmptyForANewUser() throws Exception {
        newUser("nobody", Role.USER);
        uploadAs(OWNER, "theirs.txt", new byte[10]);

        mockMvc.perform(get("/api/me/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    /** Uploads straight through the service, so a test can set up another user's files. */
    private long uploadAs(String username, String filename, byte[] content) {
        User user = userService.require(username);
        return documentService.upload(user, filename, "application/octet-stream", content).getId();
    }
}
