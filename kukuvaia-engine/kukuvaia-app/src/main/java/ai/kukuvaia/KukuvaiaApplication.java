package ai.kukuvaia;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.security.Security;
import ai.kukuvaia.mcp.McpReactorErrorHandling;
import ai.kukuvaia.provider.copilot.CopilotTokenProvider;

@EnableScheduling
@SpringBootApplication(excludeName = {
        "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
        // kukuvaia is an MCP CLIENT only — it does NOT re-expose its tools as an MCP server.
        // The aggregator below builds a List<SyncToolSpecification> by calling listTools()
        // on every registered MCP client; an unreachable client (any remote MCP peer that
        // is down during startup) makes that aggregator throw, which then tears down the
        // whole ApplicationContext. We don't use the bean, so we drop the autoconfig.
        "org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration",
        "org.springframework.ai.mcp.server.common.autoconfigure.StatelessToolCallbackConverterAutoConfiguration"
})
@ComponentScan(basePackages = {"ai.kukuvaia", "com.embabel"})
public class KukuvaiaApplication {

    private static final Logger log = LoggerFactory.getLogger(KukuvaiaApplication.class);

    /**
     * Default positive-lookup TTL for {@code java.net.InetAddress} when no SecurityManager
     * is installed. {@code -1} means "cache forever" — which means if GitHub's DNS rotation
     * hands us a broken LB IP once, every retry hits the same dead IP for the entire JVM
     * lifetime. {@code 10} seconds is short enough that the next retry (after {@code
     * CopilotTokenProvider}'s 0.5 s backoff) lands a fresh resolution, and long enough that
     * we don't hammer the OS resolver on every request.
     */
    private static final String DNS_CACHE_TTL_SECONDS = "10";

    public static void main(String[] args) {
        configureDnsCacheTtl();
        // Install BEFORE SpringApplication.run — the reactor onErrorDropped hook is global
        // (survives Spring Boot's logging-system reset), but the Logback TurboFilter does
        // NOT survive that reset. The listener below re-installs the filter once Spring
        // Boot has rebuilt the LoggerContext (during ApplicationEnvironmentPreparedEvent),
        // which fires after Logback config but before any MCP transport opens.
        McpReactorErrorHandling.install();
        SpringApplication application = new SpringApplication(KukuvaiaApplication.class);
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event ->
                McpReactorErrorHandling.installSseNoiseFilter());
        application.run(args);
    }

    static void configureDnsCacheTtl() {
        Security.setProperty("networkaddress.cache.ttl", DNS_CACHE_TTL_SECONDS);
        Security.setProperty("networkaddress.cache.negative.ttl", "0");
        log.info("JVM DNS cache TTL set to {}s (negative=0s) — required for retry to pick a fresh A-record on connect failure",
                DNS_CACHE_TTL_SECONDS);
    }
}
