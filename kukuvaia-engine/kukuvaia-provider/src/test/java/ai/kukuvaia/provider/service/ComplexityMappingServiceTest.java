package ai.kukuvaia.provider.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import ai.kukuvaia.provider.repository.ComplexityMappingRepository;
import ai.kukuvaia.provider.model.TaskComplexity;

@DisplayName("ComplexityMappingService — complexity to role resolution")
@ExtendWith(MockitoExtension.class)
class ComplexityMappingServiceTest {

    @Mock private ComplexityMappingRepository repository;

    private ComplexityMappingService service;

    @BeforeEach
    void setUp() {
        // Seed cache with DB values
        when(repository.findAll()).thenReturn(List.of(
                new ComplexityMappingRepository.ComplexityMapping("EXTRACTION", "worker", "parsing"),
                new ComplexityMappingRepository.ComplexityMapping("ANALYSIS", "supervisor", "reasoning"),
                new ComplexityMappingRepository.ComplexityMapping("STRATEGY", "advisor", "planning")
        ));
        service = new ComplexityMappingService(repository);
    }

    @Test
    @DisplayName("resolveRole — EXTRACTION → worker (from cache)")
    void resolveRole_extraction_worker() {
        assertThat(service.resolveRole(TaskComplexity.EXTRACTION)).isEqualTo("worker");
    }

    @Test
    @DisplayName("resolveRole — ANALYSIS → supervisor (from cache)")
    void resolveRole_analysis_supervisor() {
        assertThat(service.resolveRole(TaskComplexity.ANALYSIS)).isEqualTo("supervisor");
    }

    @Test
    @DisplayName("resolveRole — STRATEGY → advisor (from cache)")
    void resolveRole_strategy_advisor() {
        assertThat(service.resolveRole(TaskComplexity.STRATEGY)).isEqualTo("advisor");
    }

    @Test
    @DisplayName("resolveRole — GENERATION not in cache → falls back to DB")
    void resolveRole_generation_fallsBackToDb() {
        when(repository.findByComplexity("GENERATION")).thenReturn(
                Optional.of(new ComplexityMappingRepository.ComplexityMapping("GENERATION", "supervisor", "")));

        assertThat(service.resolveRole(TaskComplexity.GENERATION)).isEqualTo("supervisor");
    }

    @Test
    @DisplayName("resolveRole — unknown not in cache or DB → falls back to enum default")
    void resolveRole_unknown_fallsBackToEnumDefault() {
        when(repository.findByComplexity("SYNTHESIS")).thenReturn(Optional.empty());

        assertThat(service.resolveRole(TaskComplexity.SYNTHESIS)).isEqualTo("supervisor");
    }

    @Test
    @DisplayName("updateMapping — updates cache immediately")
    void updateMapping_updatesCache() {
        when(repository.update("EXTRACTION", "advisor")).thenReturn(1);

        service.updateMapping("EXTRACTION", "advisor");

        assertThat(service.resolveRole(TaskComplexity.EXTRACTION)).isEqualTo("advisor");
    }

    @Test
    @DisplayName("listMappings — returns all from DB")
    void listMappings_returnsAll() {
        when(repository.findAll()).thenReturn(List.of(
                new ComplexityMappingRepository.ComplexityMapping("EXTRACTION", "worker", ""),
                new ComplexityMappingRepository.ComplexityMapping("STRATEGY", "advisor", "")
        ));

        assertThat(service.listMappings()).hasSize(2);
    }

    @Test
    @DisplayName("TaskComplexity enum — all values have default roles")
    void taskComplexity_allHaveDefaults() {
        for (TaskComplexity complexity : TaskComplexity.values()) {
            assertThat(complexity.defaultRole()).isNotBlank();
        }
    }

    @Test
    @DisplayName("TaskComplexity — worker defaults: EXTRACTION, TRANSFORMATION, CLASSIFICATION, RETRIEVAL")
    void taskComplexity_workerDefaults() {
        assertThat(TaskComplexity.EXTRACTION.defaultRole()).isEqualTo("worker");
        assertThat(TaskComplexity.TRANSFORMATION.defaultRole()).isEqualTo("worker");
        assertThat(TaskComplexity.CLASSIFICATION.defaultRole()).isEqualTo("worker");
        assertThat(TaskComplexity.RETRIEVAL.defaultRole()).isEqualTo("worker");
    }

    @Test
    @DisplayName("TaskComplexity — supervisor defaults: ANALYSIS, GENERATION, SYNTHESIS, EVALUATION")
    void taskComplexity_supervisorDefaults() {
        assertThat(TaskComplexity.ANALYSIS.defaultRole()).isEqualTo("supervisor");
        assertThat(TaskComplexity.GENERATION.defaultRole()).isEqualTo("supervisor");
        assertThat(TaskComplexity.SYNTHESIS.defaultRole()).isEqualTo("supervisor");
        assertThat(TaskComplexity.EVALUATION.defaultRole()).isEqualTo("supervisor");
    }

    @Test
    @DisplayName("TaskComplexity — advisor default: STRATEGY")
    void taskComplexity_advisorDefault() {
        assertThat(TaskComplexity.STRATEGY.defaultRole()).isEqualTo("advisor");
    }
}
