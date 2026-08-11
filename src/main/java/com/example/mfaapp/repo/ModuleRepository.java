package com.example.mfaapp.repo;

import com.example.mfaapp.domain.Module;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ModuleRepository extends JpaRepository<Module, Long>, ModuleCatalogRepository {

    Optional<Module> findBySlug(String slug);

    /** Single statement: the module plus its ordered lessons, for the detail page. */
    @Query("""
            select distinct m from Module m
            left join fetch m.lessons l
            where m.slug = :slug
            """)
    Optional<Module> findBySlugWithLessons(@Param("slug") String slug);

    @Query("select count(l) from Lesson l where l.module.id = :moduleId")
    long countLessons(@Param("moduleId") Long moduleId);
}
