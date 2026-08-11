package com.example.mfaapp.repo;

import com.example.mfaapp.domain.Difficulty;
import com.example.mfaapp.domain.EnrollmentStatus;
import com.example.mfaapp.domain.ModuleCategory;

import java.util.EnumSet;
import java.util.Set;

/**
 * Catalog filter criteria. All present criteria compose with AND; {@code categories} and
 * {@code statuses} are OR-sets within themselves.
 */
public record CatalogFilter(
        Set<ModuleCategory> categories,
        Difficulty difficulty,
        Set<EnrollmentStatus> statuses,
        String q,
        CatalogSort sort) {

    public CatalogFilter {
        categories = categories == null || categories.isEmpty()
                ? EnumSet.noneOf(ModuleCategory.class) : EnumSet.copyOf(categories);
        statuses = statuses == null || statuses.isEmpty()
                ? EnumSet.noneOf(EnrollmentStatus.class) : EnumSet.copyOf(statuses);
        q = q == null || q.isBlank() ? null : q.trim();
        sort = sort == null ? CatalogSort.RECOMMENDED : sort;
    }

    public static CatalogFilter none() {
        return new CatalogFilter(null, null, null, null, CatalogSort.RECOMMENDED);
    }
}
