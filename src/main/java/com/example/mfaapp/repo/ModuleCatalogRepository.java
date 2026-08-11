package com.example.mfaapp.repo;

import com.example.mfaapp.domain.Role;
import com.example.mfaapp.web.dto.ModuleCardDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.Set;

/**
 * Custom catalog reads. Implemented with the Criteria API so every filter composes inside a single
 * SQL statement — lesson counts and the caller's own enrollment come back in the same projection,
 * so the statement count does not grow with the page size.
 */
public interface ModuleCatalogRepository {

    Page<ModuleCardDto> findCatalog(long userId, Set<Role> callerRoles, CatalogFilter filter, Pageable pageable);

    Optional<ModuleCardDto> findCard(long userId, Set<Role> callerRoles, String slug);
}
