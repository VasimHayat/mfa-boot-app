package com.example.mfaapp.repo;

import java.util.Locale;
import java.util.Optional;

/**
 * Allow-list of catalog sort orders. The {@code sort} request parameter is matched against this
 * enum rather than being handed to {@code Sort}, so callers cannot order by arbitrary columns.
 */
public enum CatalogSort {

    RECOMMENDED,
    NEWEST,
    SHORTEST,
    TITLE_ASC;

    public static CatalogSort parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return RECOMMENDED;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return Optional.ofNullable(switch (normalized) {
            case "RECOMMENDED", "DEFAULT" -> RECOMMENDED;
            case "NEWEST", "CREATEDAT_DESC" -> NEWEST;
            case "SHORTEST", "ESTIMATEDMINUTES_ASC" -> SHORTEST;
            case "TITLE_ASC", "TITLE", "TITLE_A_Z" -> TITLE_ASC;
            default -> null;
        }).orElse(RECOMMENDED);
    }
}
