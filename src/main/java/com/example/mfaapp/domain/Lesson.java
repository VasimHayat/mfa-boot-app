package com.example.mfaapp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "lesson",
        uniqueConstraints = @UniqueConstraint(name = "uk_lesson_module_order",
                columnNames = {"module_id", "order_index"}))
public class Lesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "module_id", nullable = false)
    private Module module;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    private ContentType contentType;

    @Column(name = "content_ref", nullable = false, length = 512)
    private String contentRef;

    @Column(name = "estimated_minutes", nullable = false)
    private int estimatedMinutes;

    protected Lesson() {
        // for JPA
    }

    Lesson(Module module, String title, int orderIndex, ContentType contentType,
           String contentRef, int estimatedMinutes) {
        this.module = module;
        this.title = title;
        this.orderIndex = orderIndex;
        this.contentType = contentType;
        this.contentRef = contentRef;
        this.estimatedMinutes = estimatedMinutes;
    }

    public Long getId() {
        return id;
    }

    public Module getModule() {
        return module;
    }

    public String getTitle() {
        return title;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public ContentType getContentType() {
        return contentType;
    }

    public String getContentRef() {
        return contentRef;
    }

    public int getEstimatedMinutes() {
        return estimatedMinutes;
    }
}
