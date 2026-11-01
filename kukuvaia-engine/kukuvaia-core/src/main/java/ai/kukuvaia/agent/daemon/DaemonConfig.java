package ai.kukuvaia.agent.daemon;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Tunables of the daemon runtime ({@code kukuvaia.daemon.*}).
 *
 * <p>{@code daily-token-budget}, {@code budget-alert-threshold} and {@code enabled} are still read by
 * {@link DaemonBudgetGuard} directly; {@code default-provider} by {@code LlmProviderService}.
 */
@Configuration
public class DaemonConfig {

    @Bean
    @ConfigurationProperties(prefix = "kukuvaia.daemon")
    DaemonProperties daemonProperties() {
        return new DaemonProperties();
    }

    public static class DaemonProperties {

        /**
         * Global tool allowlist for every daemon run, intersected with each specialist's own tools
         * (via {@code SubAgentGuard.filterTools}). Empty = no extra restriction beyond the
         * specialist allowlists. This is the "dedicated daemon tool filter" from the security review.
         */
        private List<String> allowedTools = new ArrayList<>();

        public List<String> getAllowedTools() { return allowedTools; }
        public void setAllowedTools(List<String> allowedTools) {
            this.allowedTools = allowedTools == null ? new ArrayList<>() : allowedTools;
        }

        /**
         * The persona-tools argument for {@code SubAgentFactory}: {@code null} means "no extra
         * restriction". An empty collection must NOT be passed — {@code SubAgentGuard} treats an
         * explicit empty scope as "no tools at all".
         */
        public List<String> toolScopeOrNull() {
            return allowedTools.isEmpty() ? null : List.copyOf(allowedTools);
        }
    }
}
