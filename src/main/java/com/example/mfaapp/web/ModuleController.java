package com.example.mfaapp.web;

import com.example.mfaapp.domain.Difficulty;
import com.example.mfaapp.domain.EnrollmentStatus;
import com.example.mfaapp.domain.ModuleCategory;
import com.example.mfaapp.domain.User;
import com.example.mfaapp.repo.CatalogFilter;
import com.example.mfaapp.repo.CatalogSort;
import com.example.mfaapp.service.LearningService;
import com.example.mfaapp.service.UserService;
import com.example.mfaapp.web.dto.LearningDtos.EnrollmentDto;
import com.example.mfaapp.web.dto.LearningDtos.ModuleDetailDto;
import com.example.mfaapp.web.dto.ModuleCardDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** The learning catalog. Requires a session that has cleared MFA. */
@RestController
@RequestMapping("/api/modules")
public class ModuleController {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 48;
    private static final int DEFAULT_PAGE_SIZE = 12;

    private final LearningService learningService;
    private final UserService userService;

    public ModuleController(LearningService learningService, UserService userService) {
        this.learningService = learningService;
        this.userService = userService;
    }

    /**
     * Filtered, paged catalog. Filters compose with AND; {@code status} is evaluated against the
     * caller's own enrollment and accepts {@code NOT_ENROLLED}.
     */
    @GetMapping
    public ResponseEntity<Page<ModuleCardDto>> catalog(
            Authentication authentication,
            @RequestParam(name = "category", required = false) List<ModuleCategory> category,
            @RequestParam(name = "difficulty", required = false) Difficulty difficulty,
            @RequestParam(name = "status", required = false) List<EnrollmentStatus> status,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @RequestParam(name = "sort", required = false) String sort) {

        User user = userService.require(authentication.getName());
        CatalogFilter filter = new CatalogFilter(toSet(category, ModuleCategory.class),
                difficulty, toSet(status, EnrollmentStatus.class), q, CatalogSort.parse(sort));
        int clampedSize = Math.min(MAX_PAGE_SIZE, Math.max(MIN_PAGE_SIZE, size));
        int clampedPage = Math.max(0, page);

        return ResponseEntity.ok(
                learningService.catalog(user, filter, PageRequest.of(clampedPage, clampedSize)));
    }

    @GetMapping("/{slug}")
    public ResponseEntity<ModuleDetailDto> detail(Authentication authentication,
                                                  @PathVariable String slug) {
        User user = userService.require(authentication.getName());
        return ResponseEntity.ok(learningService.detail(user, slug));
    }

    /** Idempotent: re-enrolling returns the existing enrollment with 200, never 409. */
    @PostMapping("/{slug}/enroll")
    public ResponseEntity<EnrollmentDto> enroll(Authentication authentication,
                                                @PathVariable String slug) {
        User user = userService.require(authentication.getName());
        return ResponseEntity.ok(learningService.enroll(user, slug));
    }

    @PostMapping("/{slug}/lessons/{lessonId}/complete")
    public ResponseEntity<EnrollmentDto> completeLesson(Authentication authentication,
                                                       @PathVariable String slug,
                                                       @PathVariable Long lessonId) {
        User user = userService.require(authentication.getName());
        return ResponseEntity.ok(learningService.completeLesson(user, slug, lessonId));
    }

    private static <E extends Enum<E>> Set<E> toSet(List<E> values, Class<E> type) {
        if (values == null || values.isEmpty()) {
            return EnumSet.noneOf(type);
        }
        return EnumSet.copyOf(values);
    }
}
