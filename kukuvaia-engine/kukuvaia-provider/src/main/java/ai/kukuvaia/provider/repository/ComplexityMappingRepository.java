package ai.kukuvaia.provider.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import ai.kukuvaia.provider.model.TaskComplexity;

/**
 * Repository for {@code kukuvaia.complexity_mappings} table.
 * Maps {@link TaskComplexity} to routing roles.
 */
@Component
public class ComplexityMappingRepository {

    private static final Logger log = LoggerFactory.getLogger(ComplexityMappingRepository.class);

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<ComplexityMapping> ROW_MAPPER = (rs, rowNum) -> new ComplexityMapping(
            rs.getString("complexity"),
            rs.getString("role"),
            rs.getString("description")
    );

    public ComplexityMappingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ComplexityMapping> findByComplexity(String complexity) {
        List<ComplexityMapping> results = jdbcTemplate.query(
                "SELECT complexity, role, description FROM kukuvaia.complexity_mappings WHERE complexity = ?",
                ROW_MAPPER, complexity);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public List<ComplexityMapping> findAll() {
        return jdbcTemplate.query(
                "SELECT complexity, role, description FROM kukuvaia.complexity_mappings ORDER BY complexity",
                ROW_MAPPER);
    }

    public int update(String complexity, String role) {
        return jdbcTemplate.update(
                "UPDATE kukuvaia.complexity_mappings SET role = ?, updated_at = NOW() WHERE complexity = ?",
                role, complexity);
    }

    public record ComplexityMapping(String complexity, String role, String description) {}
}
