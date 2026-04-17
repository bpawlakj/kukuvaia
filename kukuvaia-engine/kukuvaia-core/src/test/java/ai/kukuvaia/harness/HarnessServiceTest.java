package ai.kukuvaia.harness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("HarnessService — rule resolution and hierarchy")
@ExtendWith(MockitoExtension.class)
class HarnessServiceTest {

    @Mock private RuleSetRepository ruleSetRepository;
    @Mock private RuleRepository ruleRepository;
    @Mock private GroupRepository groupRepository;

    private HarnessService service;

    private final String userId = "test-user-123";
    private final UUID groupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new HarnessService(ruleSetRepository, ruleRepository, groupRepository);
    }

    private RuleSetRecord ruleSet(UUID id, String scope, String ownerType, Object ownerId) {
        return new RuleSetRecord(id, "test", null, scope, ownerType,
                ownerId != null ? ownerId.toString() : null,
                0, true, Instant.now(), Instant.now());
    }

    private RuleRecord rule(String key, String type, String content) {
        return new RuleRecord(UUID.randomUUID(), UUID.randomUUID(), key, type, content,
                List.of(), Map.of("always", true), 0, true, 1, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("no rules — returns empty string")
    void noRules_returnsEmpty() {
        when(ruleSetRepository.findByScope("platform")).thenReturn(List.of());
        when(groupRepository.findGroupIdsByUserId(userId)).thenReturn(List.of());
        when(ruleSetRepository.findByOwner("user", userId)).thenReturn(List.of());

        String result = service.compileRules(userId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("platform rules — included for all users")
    void platformRules_included() {
        UUID rsId = UUID.randomUUID();
        when(ruleSetRepository.findByScope("platform")).thenReturn(List.of(ruleSet(rsId, "platform", "system", null)));
        when(ruleRepository.findByRuleSetId(rsId)).thenReturn(List.of(
                rule("di", "instruction", "Use constructor injection only")
        ));
        when(groupRepository.findGroupIdsByUserId(userId)).thenReturn(List.of());
        when(ruleSetRepository.findByOwner("user", userId)).thenReturn(List.of());

        String result = service.compileRules(userId);

        assertThat(result).contains("constructor injection");
        assertThat(result).contains("Instructions");
    }

    @Test
    @DisplayName("group rules — included for group members")
    void groupRules_includedForMembers() {
        UUID rsId = UUID.randomUUID();
        when(ruleSetRepository.findByScope("platform")).thenReturn(List.of());
        when(groupRepository.findGroupIdsByUserId(userId)).thenReturn(List.of(groupId));
        when(ruleSetRepository.findByOwner("group", groupId)).thenReturn(List.of(ruleSet(rsId, "group", "group", groupId)));
        when(ruleRepository.findByRuleSetId(rsId)).thenReturn(List.of(
                rule("framework", "context", "This team uses Spring Boot 3.4")
        ));
        when(ruleSetRepository.findByOwner("user", userId)).thenReturn(List.of());

        String result = service.compileRules(userId);

        assertThat(result).contains("Spring Boot 3.4");
        assertThat(result).contains("Context");
    }

    @Test
    @DisplayName("user rule overrides group rule — same key")
    void userOverridesGroup_sameKey() {
        UUID groupRsId = UUID.randomUUID();
        UUID userRsId = UUID.randomUUID();

        when(ruleSetRepository.findByScope("platform")).thenReturn(List.of());
        when(groupRepository.findGroupIdsByUserId(userId)).thenReturn(List.of(groupId));
        when(ruleSetRepository.findByOwner("group", groupId)).thenReturn(List.of(ruleSet(groupRsId, "group", "group", groupId)));
        when(ruleRepository.findByRuleSetId(groupRsId)).thenReturn(List.of(
                rule("testing", "instruction", "Use Hamcrest for assertions")
        ));
        when(ruleSetRepository.findByOwner("user", userId)).thenReturn(List.of(ruleSet(userRsId, "user", "user", userId)));
        when(ruleRepository.findByRuleSetId(userRsId)).thenReturn(List.of(
                rule("testing", "instruction", "Use AssertJ for assertions")
        ));

        String result = service.compileRules(userId);

        assertThat(result).contains("AssertJ");
        assertThat(result).doesNotContain("Hamcrest");
    }

    @Test
    @DisplayName("multiple rule types — grouped by type in output")
    void multipleTypes_grouped() {
        UUID rsId = UUID.randomUUID();
        when(ruleSetRepository.findByScope("platform")).thenReturn(List.of(ruleSet(rsId, "platform", "system", null)));
        when(ruleRepository.findByRuleSetId(rsId)).thenReturn(List.of(
                rule("di", "instruction", "Use constructor injection"),
                rule("no-autowired", "constraint", "Never use @Autowired on fields"),
                rule("team-info", "context", "Java 21 project")
        ));
        when(groupRepository.findGroupIdsByUserId(userId)).thenReturn(List.of());
        when(ruleSetRepository.findByOwner("user", userId)).thenReturn(List.of());

        String result = service.compileRules(userId);

        assertThat(result).contains("### Instructions");
        assertThat(result).contains("### Constraints");
        assertThat(result).contains("### Context");
    }

    @Test
    @DisplayName("null userId — only platform rules")
    void nullUserId_platformOnly() {
        UUID rsId = UUID.randomUUID();
        when(ruleSetRepository.findByScope("platform")).thenReturn(List.of(ruleSet(rsId, "platform", "system", null)));
        when(ruleRepository.findByRuleSetId(rsId)).thenReturn(List.of(
                rule("safe", "instruction", "Be safe")
        ));

        String result = service.compileRules(null);

        assertThat(result).contains("Be safe");
    }

    @Test
    @DisplayName("cache — second call uses cached value")
    void cache_secondCallUsesCached() {
        when(ruleSetRepository.findByScope("platform")).thenReturn(List.of());
        when(groupRepository.findGroupIdsByUserId(userId)).thenReturn(List.of());
        when(ruleSetRepository.findByOwner("user", userId)).thenReturn(List.of());

        service.resolveForUser(userId);
        service.resolveForUser(userId);

        // findByScope called only once (second call hits cache)
        org.mockito.Mockito.verify(ruleSetRepository, org.mockito.Mockito.times(1)).findByScope("platform");
    }

    @Test
    @DisplayName("invalidateCache — forces re-resolution")
    void invalidateCache_forcesReResolution() {
        when(ruleSetRepository.findByScope("platform")).thenReturn(List.of());
        when(groupRepository.findGroupIdsByUserId(userId)).thenReturn(List.of());
        when(ruleSetRepository.findByOwner("user", userId)).thenReturn(List.of());

        service.resolveForUser(userId);
        service.invalidateCache(userId);
        service.resolveForUser(userId);

        org.mockito.Mockito.verify(ruleSetRepository, org.mockito.Mockito.times(2)).findByScope("platform");
    }
}
