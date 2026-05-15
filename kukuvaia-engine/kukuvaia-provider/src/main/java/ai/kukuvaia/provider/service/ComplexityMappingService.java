package ai.kukuvaia.provider.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ai.kukuvaia.provider.repository.ComplexityMappingRepository;
import ai.kukuvaia.provider.model.TaskComplexity;

/**
 * Resolves {@link TaskComplexity} to routing roles via DB mappings.
 * Caches mappings in-memory for fast per-action resolution.
 * Falls back to {@link TaskComplexity#defaultRole()} when no DB mapping exists.
 */
@Service
public class ComplexityMappingService {

    private static final Logger log = LoggerFactory.getLogger(ComplexityMappingService.class);

    private final ComplexityMappingRepository repository;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public ComplexityMappingService(ComplexityMappingRepository repository) {
        this.repository = repository;
        refreshCache();
    }

    /**
     * Resolve complexity to a routing role.
     * Checks cache first, then DB, then falls back to enum default.
     */
    public String resolveRole(TaskComplexity complexity) {
        String cached = cache.get(complexity.name());
        if (cached != null) return cached;

        return repository.findByComplexity(complexity.name())
                .map(ComplexityMappingRepository.ComplexityMapping::role)
                .orElseGet(() -> {
                    log.debug("No DB mapping for complexity '{}', using default: {}",
                            complexity, complexity.defaultRole());
                    return complexity.defaultRole();
                });
    }

    /**
     * List all mappings (for API/admin).
     */
    public List<ComplexityMappingRepository.ComplexityMapping> listMappings() {
        return repository.findAll();
    }

    /**
     * Update a mapping and refresh cache.
     */
    public boolean updateMapping(String complexity, String role) {
        int updated = repository.update(complexity, role);
        if (updated > 0) {
            cache.put(complexity, role);
            log.info("Updated complexity mapping: {} → {}", complexity, role);
        }
        return updated > 0;
    }

    /**
     * Reload all mappings from DB into cache.
     */
    public void refreshCache() {
        cache.clear();
        repository.findAll().forEach(m -> cache.put(m.complexity(), m.role()));
        log.info("Complexity mapping cache refreshed: {} entries", cache.size());
    }
}
