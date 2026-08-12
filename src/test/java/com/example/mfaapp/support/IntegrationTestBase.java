package com.example.mfaapp.support;

import com.example.mfaapp.domain.ContentType;
import com.example.mfaapp.domain.Difficulty;
import com.example.mfaapp.domain.Module;
import com.example.mfaapp.domain.ModuleCategory;
import com.example.mfaapp.domain.Role;
import com.example.mfaapp.domain.User;
import com.example.mfaapp.repo.EnrollmentRepository;
import com.example.mfaapp.repo.LessonProgressRepository;
import com.example.mfaapp.repo.MfaSecretRepository;
import com.example.mfaapp.repo.ModuleRepository;
import com.example.mfaapp.repo.StoredDocumentRepository;
import com.example.mfaapp.repo.UserRepository;
import com.example.mfaapp.service.DocumentService;
import com.example.mfaapp.service.MfaService;
import com.example.mfaapp.service.SeaweedFsClient;
import com.example.mfaapp.service.TotpService;
import com.example.mfaapp.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Shared wiring, a clean database per test, and fixture builders. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
public abstract class IntegrationTestBase {

    protected static final String PASSWORD = "Correct@Horse9";

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected UserRepository users;
    @Autowired
    protected ModuleRepository modules;
    @Autowired
    protected EnrollmentRepository enrollments;
    @Autowired
    protected LessonProgressRepository lessonProgress;
    @Autowired
    protected MfaSecretRepository mfaSecrets;
    @Autowired
    protected StoredDocumentRepository storedDocuments;
    @Autowired
    protected SeaweedFsClient seaweedFsClient;
    @Autowired
    protected UserService userService;
    @Autowired
    protected DocumentService documentService;
    @Autowired
    protected MfaService mfaService;
    @Autowired
    protected TotpService totpService;
    @Autowired
    protected Clock clock;

    protected MutableClock testClock() {
        return (MutableClock) clock;
    }

    /**
     * The seeder is disabled under the test profile, so each test starts from an empty schema and
     * builds only the rows its assertions depend on.
     */
    @BeforeEach
    void resetDatabase() {
        testClock().setInstant(TestClockConfig.START);
        lessonProgress.deleteAll();
        enrollments.deleteAll();
        mfaSecrets.deleteAll();
        storedDocuments.deleteAll();
        modules.deleteAll();
        users.deleteAll();
        objectStore().clear();
    }

    protected InMemoryObjectStore objectStore() {
        return (InMemoryObjectStore) seaweedFsClient;
    }

    protected User newUser(String username, Role... roles) {
        return userService.createUser(username, PASSWORD, EnumSet.copyOf(Set.of(roles)));
    }

    /** Takes a user all the way through enrolment and returns the shared secret. */
    protected String enrollMfa(String username) {
        String secret = mfaService.beginSetup(username).secretBase32();
        String code = totpService.generateCode(secret, totpService.currentTimeStep());
        MfaService.ConfirmResult result = mfaService.confirm(username, code);
        if (result.outcome() != MfaService.ConfirmOutcome.SUCCESS) {
            throw new IllegalStateException("Fixture failed to enrol MFA: " + result.outcome());
        }
        return secret;
    }

    /** Same as {@link #enrollMfa} but also hands back the plaintext recovery codes. */
    protected EnrolledMfa enrollMfaWithCodes(String username) {
        String secret = mfaService.beginSetup(username).secretBase32();
        String code = totpService.generateCode(secret, totpService.currentTimeStep());
        MfaService.ConfirmResult result = mfaService.confirm(username, code);
        if (result.outcome() != MfaService.ConfirmOutcome.SUCCESS) {
            throw new IllegalStateException("Fixture failed to enrol MFA: " + result.outcome());
        }
        return new EnrolledMfa(secret, result.recoveryCodes());
    }

    public record EnrolledMfa(String secretBase32, List<String> recoveryCodes) {
    }

    protected String currentTotp(String secret) {
        return totpService.generateCode(secret, totpService.currentTimeStep());
    }

    /**
     * A published module with {@code lessonCount} lessons of ten minutes each.
     *
     * @param requiredRole pass null for an ungated module
     */
    protected Module newModule(String slug, String title, ModuleCategory category, Difficulty difficulty,
                               int lessonCount, Role requiredRole, int sortOrder) {
        Instant createdAt = clock.instant().minusSeconds(sortOrder * 3600L);
        Module module = new Module(slug, title, title + " summary text.",
                title + " long form description.", category, difficulty, lessonCount * 10, null,
                true, requiredRole, sortOrder, createdAt);
        for (int i = 1; i <= lessonCount; i++) {
            module.addLesson(title + " lesson " + i, i,
                    switch (i % 3) {
                        case 0 -> ContentType.QUIZ;
                        case 1 -> ContentType.VIDEO;
                        default -> ContentType.ARTICLE;
                    },
                    "content/" + slug + "/" + i, 10);
        }
        return modules.save(module);
    }

    protected Module newModule(String slug, String title, ModuleCategory category,
                               Difficulty difficulty, int lessonCount) {
        return newModule(slug, title, category, difficulty, lessonCount, null, 1);
    }

    protected String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
