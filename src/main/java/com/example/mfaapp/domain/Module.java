package com.example.mfaapp.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A learning module.
 *
 * <p>Note this type shadows {@code java.lang.Module}; every class outside this package must import
 * it explicitly (a single-type import wins over the implicit {@code java.lang} import-on-demand).
 */
@Entity
@Table(name = "learning_module",
        uniqueConstraints = @UniqueConstraint(name = "uk_learning_module_slug", columnNames = "slug"))
public class Module {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String slug;

    @Column(nullable = false, length = 200)
    private String title;

    /** Short blurb for the catalog card. */
    @Column(nullable = false, length = 400)
    private String summary;

    /** Long form copy for the detail page. */
    @Column(nullable = false, length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ModuleCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Difficulty difficulty;

    @Column(name = "estimated_minutes", nullable = false)
    private int estimatedMinutes;

    @Column(name = "thumbnail_url", length = 512)
    private String thumbnailUrl;

    @Column(nullable = false)
    private boolean published;

    /** When set, the module is hidden from users who do not hold this role. */
    @Enumerated(EnumType.STRING)
    @Column(name = "required_role", length = 40)
    private Role requiredRole;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "module", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex asc")
    private List<Lesson> lessons = new ArrayList<>();

    /**
     * Mapped so the catalog query can LEFT JOIN to the caller's own enrollment. Never navigated
     * from application code — doing so would load every user's enrollment for the module.
     */
    @OneToMany(mappedBy = "module")
    private Set<Enrollment> enrollments = new LinkedHashSet<>();

    protected Module() {
        // for JPA
    }

    public Module(String slug, String title, String summary, String description,
                  ModuleCategory category, Difficulty difficulty, int estimatedMinutes,
                  String thumbnailUrl, boolean published, Role requiredRole, int sortOrder,
                  Instant createdAt) {
        this.slug = slug;
        this.title = title;
        this.summary = summary;
        this.description = description;
        this.category = category;
        this.difficulty = difficulty;
        this.estimatedMinutes = estimatedMinutes;
        this.thumbnailUrl = thumbnailUrl;
        this.published = published;
        this.requiredRole = requiredRole;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Lesson addLesson(String title, int orderIndex, ContentType contentType,
                            String contentRef, int estimatedMinutes) {
        Lesson lesson = new Lesson(this, title, orderIndex, contentType, contentRef, estimatedMinutes);
        this.lessons.add(lesson);
        return lesson;
    }

    public Long getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public String getDescription() {
        return description;
    }

    public ModuleCategory getCategory() {
        return category;
    }

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public int getEstimatedMinutes() {
        return estimatedMinutes;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public boolean isPublished() {
        return published;
    }

    public Role getRequiredRole() {
        return requiredRole;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<Lesson> getLessons() {
        return lessons;
    }

    /** True when the module is published and visible to a caller holding {@code callerRoles}. */
    public boolean isVisibleTo(Set<Role> callerRoles) {
        return published && (requiredRole == null || callerRoles.contains(requiredRole));
    }
}
