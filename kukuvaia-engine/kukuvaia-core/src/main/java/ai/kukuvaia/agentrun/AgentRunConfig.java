package ai.kukuvaia.agentrun;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Wiring + tunables for the agent-run subsystem (P23).
 *
 * <p>{@link #agentRunExecutor()} is a small fixed pool used for async run execution. Pool size
 * is intentionally low (4) — agent runs are LLM-bound, so concurrency is bounded by upstream
 * model throughput, not local CPU.
 */
@Configuration
public class AgentRunConfig {

    @Bean
    Executor agentRunExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            var t = new Thread(r, "agent-run");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    @ConfigurationProperties(prefix = "kukuvaia.agent-runs")
    AgentRunProperties agentRunProperties() {
        return new AgentRunProperties();
    }

    public static class AgentRunProperties {
        /** Maximum escalation depth — primary + N escalations. Default 1 (Haiku → Sonnet). */
        private int maxEscalationDepth = 1;
        /** Hard cap on re-runs per row (safety guard). */
        private int maxReruns = 2;

        public int getMaxEscalationDepth() { return maxEscalationDepth; }
        public void setMaxEscalationDepth(int v) { this.maxEscalationDepth = v; }
        public int getMaxReruns() { return maxReruns; }
        public void setMaxReruns(int v) { this.maxReruns = v; }
    }
}
