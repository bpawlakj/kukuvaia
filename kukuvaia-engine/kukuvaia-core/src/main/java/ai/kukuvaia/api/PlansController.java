package ai.kukuvaia.api;

import ai.kukuvaia.plans.PlanEntry;
import ai.kukuvaia.plans.PlansRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * GET /api/plans — plan registry read endpoints backing the CLI picker and
 * the natural-language {@code list_plans} tool.
 *
 * User scoping: until the auth layer surfaces an authenticated user id on
 * controller parameters, callers pass {@code userId} as a query param. The
 * same hardcoded "bartek" fallback used elsewhere (see {@code TodoCommand})
 * applies when the parameter is absent — a known shortcut tracked by the
 * open auth TODO. The repository still filters by user id, so cross-user
 * reads are impossible as long as the id passed is correct.
 */
@RestController
@RequestMapping("/api/plans")
public class PlansController {

    private static final Logger log = LoggerFactory.getLogger(PlansController.class);
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final String DEFAULT_USER_ID = "bartek";

    private final PlansRepository plansRepository;

    public PlansController(PlansRepository plansRepository) {
        this.plansRepository = plansRepository;
    }

    @GetMapping
    public ResponseEntity<List<PlanEntry>> listPlans(
            @RequestParam(name = "userId", required = false) String userId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", required = false, defaultValue = "50") int limit) {
        String resolvedUser = resolveUserId(userId);
        int safeLimit = clampLimit(limit);
        List<PlanEntry> plans = plansRepository.findByUser(resolvedUser, status, safeLimit);
        log.debug("listPlans: user={} status={} limit={} → {} rows",
                resolvedUser, status, safeLimit, plans.size());
        return ResponseEntity.ok(plans);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlanEntry> getPlan(
            @PathVariable("id") UUID id,
            @RequestParam(name = "userId", required = false) String userId) {
        String resolvedUser = resolveUserId(userId);
        Optional<PlanEntry> entry = plansRepository.findById(id, resolvedUser);
        return entry.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    private static String resolveUserId(String userId) {
        if (userId == null || userId.isBlank()) return DEFAULT_USER_ID;
        return userId;
    }

    private static int clampLimit(int requested) {
        if (requested <= 0) return DEFAULT_LIMIT;
        return Math.min(requested, MAX_LIMIT);
    }
}
