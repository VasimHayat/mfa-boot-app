package com.example.mfaapp.repo;

import com.example.mfaapp.domain.Difficulty;
import com.example.mfaapp.domain.Enrollment;
import com.example.mfaapp.domain.EnrollmentStatus;
import com.example.mfaapp.domain.Lesson;
import com.example.mfaapp.domain.LessonProgress;
import com.example.mfaapp.domain.Module;
import com.example.mfaapp.domain.ModuleCategory;
import com.example.mfaapp.domain.Role;
import com.example.mfaapp.web.dto.ModuleCardDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Criteria-API implementation of the catalog reads.
 *
 * <p>Exactly two statements are issued per catalog page — one COUNT and one SELECT — no matter how
 * many rows the page holds. Lesson totals and the caller's completed-lesson count are scalar
 * subqueries inside the same SELECT, and the caller's enrollment is a restricted LEFT JOIN, so
 * there is no per-row follow-up query.
 */
public class ModuleCatalogRepositoryImpl implements ModuleCatalogRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Page<ModuleCardDto> findCatalog(long userId, Set<Role> callerRoles, CatalogFilter filter,
                                           Pageable pageable) {
        int page = Math.max(0, pageable.getPageNumber());
        int size = pageable.getPageSize() < 1 ? 12 : pageable.getPageSize();

        long total = count(userId, callerRoles, filter);
        if (total == 0) {
            return new PageImpl<>(List.of(), PageRequest.of(page, size), 0);
        }

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<Module> root = cq.from(Module.class);
        Join<Module, Enrollment> enrollment = callerEnrollmentJoin(cb, root, userId);

        cq.multiselect(
                root.get("id"),
                root.get("slug"),
                root.get("title"),
                root.get("summary"),
                root.get("category"),
                root.get("difficulty"),
                root.get("estimatedMinutes"),
                root.get("thumbnailUrl"),
                lessonCountSubquery(cb, cq, root),
                enrollment.get("status"),
                completedLessonsSubquery(cb, cq, root, userId));
        cq.where(predicates(cb, root, enrollment, callerRoles, filter).toArray(Predicate[]::new));
        cq.orderBy(orderBy(cb, root, filter.sort()));

        List<Tuple> rows = em.createQuery(cq)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();

        return new PageImpl<>(rows.stream().map(ModuleCatalogRepositoryImpl::toCard).toList(),
                PageRequest.of(page, size), total);
    }

    @Override
    public Optional<ModuleCardDto> findCard(long userId, Set<Role> callerRoles, String slug) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<Module> root = cq.from(Module.class);
        Join<Module, Enrollment> enrollment = callerEnrollmentJoin(cb, root, userId);

        cq.multiselect(
                root.get("id"),
                root.get("slug"),
                root.get("title"),
                root.get("summary"),
                root.get("category"),
                root.get("difficulty"),
                root.get("estimatedMinutes"),
                root.get("thumbnailUrl"),
                lessonCountSubquery(cb, cq, root),
                enrollment.get("status"),
                completedLessonsSubquery(cb, cq, root, userId));

        List<Predicate> where = predicates(cb, root, enrollment, callerRoles, CatalogFilter.none());
        where.add(cb.equal(root.get("slug"), slug));
        cq.where(where.toArray(Predicate[]::new));

        return em.createQuery(cq).setMaxResults(1).getResultStream()
                .findFirst()
                .map(ModuleCatalogRepositoryImpl::toCard);
    }

    private long count(long userId, Set<Role> callerRoles, CatalogFilter filter) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Module> root = cq.from(Module.class);
        Join<Module, Enrollment> enrollment = callerEnrollmentJoin(cb, root, userId);
        cq.select(cb.countDistinct(root.get("id")));
        cq.where(predicates(cb, root, enrollment, callerRoles, filter).toArray(Predicate[]::new));
        return Optional.ofNullable(em.createQuery(cq).getSingleResult()).orElse(0L);
    }

    /**
     * LEFT JOIN restricted to the caller's own enrollment. Because the ON clause pins the user, at
     * most one enrollment row can match per module, so the join cannot duplicate catalog rows.
     */
    private Join<Module, Enrollment> callerEnrollmentJoin(CriteriaBuilder cb, Root<Module> root, long userId) {
        Join<Module, Enrollment> enrollment = root.join("enrollments", JoinType.LEFT);
        enrollment.on(cb.equal(enrollment.get("user").get("id"), userId));
        return enrollment;
    }

    private Subquery<Long> lessonCountSubquery(CriteriaBuilder cb, CriteriaQuery<?> cq, Root<Module> root) {
        Subquery<Long> sub = cq.subquery(Long.class);
        Root<Lesson> lesson = sub.from(Lesson.class);
        return sub.select(cb.count(lesson.get("id")))
                .where(cb.equal(lesson.get("module"), root));
    }

    private Subquery<Long> completedLessonsSubquery(CriteriaBuilder cb, CriteriaQuery<?> cq,
                                                    Root<Module> root, long userId) {
        Subquery<Long> sub = cq.subquery(Long.class);
        Root<LessonProgress> progress = sub.from(LessonProgress.class);
        Join<LessonProgress, Enrollment> enrollment = progress.join("enrollment");
        return sub.select(cb.count(progress.get("id")))
                .where(cb.equal(enrollment.get("module"), root),
                        cb.equal(enrollment.get("user").get("id"), userId),
                        cb.isTrue(progress.get("completed")));
    }

    private List<Predicate> predicates(CriteriaBuilder cb, Root<Module> root,
                                       Join<Module, Enrollment> enrollment,
                                       Set<Role> callerRoles, CatalogFilter filter) {
        List<Predicate> where = new ArrayList<>();
        where.add(cb.isTrue(root.get("published")));

        Predicate ungated = cb.isNull(root.get("requiredRole"));
        where.add(callerRoles.isEmpty() ? ungated : cb.or(ungated, root.get("requiredRole").in(callerRoles)));

        if (filter.q() != null) {
            String like = "%" + filter.q().toLowerCase(Locale.ROOT) + "%";
            where.add(cb.or(cb.like(cb.lower(root.get("title")), like),
                    cb.like(cb.lower(root.get("summary")), like)));
        }
        Set<ModuleCategory> categories = filter.categories();
        if (!categories.isEmpty()) {
            where.add(root.get("category").in(categories));
        }
        Difficulty difficulty = filter.difficulty();
        if (difficulty != null) {
            where.add(cb.equal(root.get("difficulty"), difficulty));
        }

        Set<EnrollmentStatus> statuses = filter.statuses();
        if (!statuses.isEmpty()) {
            List<Predicate> anyOf = new ArrayList<>();
            if (statuses.contains(EnrollmentStatus.NOT_ENROLLED)) {
                anyOf.add(cb.isNull(enrollment.get("id")));
            }
            Set<EnrollmentStatus> persisted = EnumSet.noneOf(EnrollmentStatus.class);
            statuses.stream().filter(EnrollmentStatus::isPersistable).forEach(persisted::add);
            if (!persisted.isEmpty()) {
                anyOf.add(enrollment.get("status").in(persisted));
            }
            where.add(cb.or(anyOf.toArray(Predicate[]::new)));
        }
        return where;
    }

    private List<Order> orderBy(CriteriaBuilder cb, Root<Module> root, CatalogSort sort) {
        // The trailing id keeps paging stable when the primary key of the sort is not unique.
        return switch (sort) {
            case RECOMMENDED -> List.of(cb.asc(root.get("sortOrder")), cb.asc(root.get("id")));
            case NEWEST -> List.of(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));
            case SHORTEST -> List.of(cb.asc(root.get("estimatedMinutes")), cb.asc(root.get("id")));
            case TITLE_ASC -> List.of(cb.asc(cb.lower(root.get("title"))), cb.asc(root.get("id")));
        };
    }

    private static ModuleCardDto toCard(Tuple t) {
        return ModuleCardDto.of(
                (Long) t.get(0),
                (String) t.get(1),
                (String) t.get(2),
                (String) t.get(3),
                (ModuleCategory) t.get(4),
                (Difficulty) t.get(5),
                (Integer) t.get(6),
                (String) t.get(7),
                (Long) t.get(8),
                (EnrollmentStatus) t.get(9),
                (Long) t.get(10));
    }
}
