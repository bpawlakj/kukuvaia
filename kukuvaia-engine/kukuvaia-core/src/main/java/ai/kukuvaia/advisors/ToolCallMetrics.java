package ai.kukuvaia.advisors;

import ai.kukuvaia.output.SpanEventEmitter;
import ai.kukuvaia.security.ErrorSanitizer;
import ai.kukuvaia.tools.PlanningTools;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Times all Spring AI @Tool method executions and records them as Micrometer metrics.
 * Provides per-tool-name duration histograms and call counts.
 *
 * Also enriches the live span stream:
 *  - on start: adds the first String argument as {@code kukuvaia.summary} preview
 *    (truncated to 60 chars) so the CLI tree shows "tool:grep  pattern=foo"
 *  - on error: adds {@code kukuvaia.error.type} and {@code kukuvaia.error.message}
 *    (PII-sanitised, capped at 200 chars) so the failure surfaces inline
 *
 * Caveat: AOP intercepts only Spring-proxied calls. If Spring AI invokes a tool
 * via direct reflection on the unwrapped bean, this aspect will not fire.
 */
@Aspect
@Component
public class ToolCallMetrics {

    private static final Logger log = LoggerFactory.getLogger(ToolCallMetrics.class);
    private static final int SUMMARY_MAX = 60;
    private static final int ERROR_MSG_MAX = 200;

    private final MeterRegistry meterRegistry;
    private final SpanEventEmitter spanEmitter;
    private final ErrorSanitizer errorSanitizer;

    public ToolCallMetrics(MeterRegistry meterRegistry,
                           SpanEventEmitter spanEmitter,
                           ErrorSanitizer errorSanitizer) {
        this.meterRegistry = meterRegistry;
        this.spanEmitter = spanEmitter;
        this.errorSanitizer = errorSanitizer;
    }

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object timeToolCall(ProceedingJoinPoint joinPoint) throws Throwable {
        String toolName = joinPoint.getSignature().getName();
        Timer.Sample sample = Timer.start(meterRegistry);
        String status = "success";

        String sessionId = PlanningTools.getCurrentSessionId();
        Map<String, Object> startAttrs = new LinkedHashMap<>();
        String preview = firstStringArg(joinPoint.getArgs());
        if (preview != null) {
            startAttrs.put("kukuvaia.summary", preview);
        }
        SpanEventEmitter.Handle span = spanEmitter.start(sessionId, null,
                "tool:" + toolName, startAttrs);

        Throwable caught = null;
        try {
            return joinPoint.proceed();
        } catch (Throwable t) {
            status = "error";
            caught = t;
            throw t;
        } finally {
            sample.stop(Timer.builder("kukuvaia.tool.call.duration")
                    .tag("tool", toolName)
                    .tag("status", status)
                    .register(meterRegistry));

            Map<String, Object> endAttrs = new LinkedHashMap<>();
            endAttrs.put("kukuvaia.status", status);
            if (preview != null) {
                endAttrs.put("kukuvaia.summary", preview);
            }
            if (caught != null) {
                endAttrs.put("kukuvaia.error.type", caught.getClass().getSimpleName());
                String raw = caught.getMessage();
                if (raw != null) {
                    endAttrs.put("kukuvaia.error.message", truncate(errorSanitizer.sanitize(raw), ERROR_MSG_MAX));
                }
            }
            spanEmitter.end(span, endAttrs);
            log.debug("Tool call timed: {} status={} preview={}", toolName, status, preview);
        }
    }

    /**
     * Picks the first non-null String argument as a display preview. Most tools
     * have a primary string input (path, query, command, pattern); this keeps
     * the rule dead-simple without per-tool knowledge.
     */
    private static String firstStringArg(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof String s && !s.isBlank()) {
                return truncate(s, SUMMARY_MAX);
            }
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        String clean = s.replaceAll("\\s+", " ").trim();
        if (clean.length() <= max) return clean;
        return clean.substring(0, max - 1) + "…";
    }
}
