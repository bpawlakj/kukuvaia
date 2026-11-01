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

        /** Kill switch: when false no daemon run is admitted and no cron job is scheduled. */
        private boolean enabled = true;
        /** Daemon runs executing at the same time (all kinds together). */
        private int maxConcurrent = 2;
        /** Admitted runs waiting for a slot; beyond this, new runs are SKIPPED (backpressure). */
        private int maxQueued = 100;

        /** Load {@code .kukuvaia/daemon/*.yaml} (CLI mode only; keep false on servers). */
        private boolean projectJobs = false;
        /** Threads of the dedicated cron scheduler (firing only submits to the runtime). */
        private int schedulerPoolSize = 2;
        /** Time zone for cron expressions; empty = system default. */
        private String zone = "";

        public boolean isProjectJobs() { return projectJobs; }
        public void setProjectJobs(boolean projectJobs) { this.projectJobs = projectJobs; }
        public int getSchedulerPoolSize() { return schedulerPoolSize; }
        public void setSchedulerPoolSize(int size) { this.schedulerPoolSize = Math.max(1, size); }
        public String getZone() { return zone; }
        public void setZone(String zone) { this.zone = zone == null ? "" : zone; }
        public java.time.ZoneId zoneId() {
            return zone.isBlank() ? java.time.ZoneId.systemDefault() : java.time.ZoneId.of(zone);
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = Math.max(1, maxConcurrent); }
        public int getMaxQueued() { return maxQueued; }
        public void setMaxQueued(int maxQueued) { this.maxQueued = Math.max(0, maxQueued); }

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
