package ai.kukuvaia.provider.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Logs provider name, model, and token usage for every LLM call.
 * Emits Micrometer metrics: {@code kukuvaia.llm.request.duration} (Timer)
 * and {@code kukuvaia.llm.tokens.total} (Counter).
 *
 * Runs as first advisor in chain — captures metadata for all calls
 * (interactive and daemon, parent and sub-agent).
 */
@Component
public class ProviderAuditLog implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ProviderAuditLog.class);
    private static final String CTX_PROVIDER = "kukuvaia.provider";
    private static final String CTX_CONTEXT = "kukuvaia.executionContext";
    private static final String CTX_SPECIALIST = "kukuvaia.specialist";
    private static final String CTX_TIMER_SAMPLE = "kukuvaia.timerSample";
    private static final String CTX_MODEL = "kukuvaia.model";

    private final MeterRegistry meterRegistry;

    public ProviderAuditLog(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // Defensive: clear any MDC leaked from a prior failed advisor chain on this thread.
        // BaseAdvisor has no onError hook — if a downstream advisor threw, after() never ran.
        clearMdc();

        String provider = (String) request.context().getOrDefault(CTX_PROVIDER, "unknown");
        String executionContext = (String) request.context().getOrDefault(CTX_CONTEXT, "interactive");
        String specialist = (String) request.context().getOrDefault(CTX_SPECIALIST, "parent");
        String model = resolveModel(request);

        MDC.put("provider", provider);
        MDC.put("executionContext", executionContext);
        MDC.put("specialist", specialist);

        log.info("LLM call: provider={}, model={}, context={}, specialist={}, messageCount={}",
                provider, model, executionContext, specialist,
                request.prompt().getInstructions().size());

        Timer.Sample sample = Timer.start(meterRegistry);

        return request.mutate()
                .context(CTX_TIMER_SAMPLE, sample)
                .context(CTX_MODEL, model)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        try {
            var ctx = response.context();
            String provider = (String) ctx.getOrDefault(CTX_PROVIDER, "unknown");
            String executionContext = (String) ctx.getOrDefault(CTX_CONTEXT, "interactive");
            String specialist = (String) ctx.getOrDefault(CTX_SPECIALIST, "parent");
            String model = (String) ctx.getOrDefault(CTX_MODEL, "unknown");

            if (ctx.get(CTX_TIMER_SAMPLE) instanceof Timer.Sample sample) {
                sample.stop(Timer.builder("kukuvaia.llm.request.duration")
                        .tag("provider", provider)
                        .tag("model", model)
                        .tag("context", executionContext)
                        .tag("specialist", specialist)
                        .tag("status", "success")
                        .register(meterRegistry));
            }

            if (response.chatResponse() != null) {
                var usage = response.chatResponse().getMetadata().getUsage();
                if (usage != null) {
                    Counter.builder("kukuvaia.llm.tokens.total")
                            .tag("provider", provider)
                            .tag("model", model)
                            .tag("type", "prompt")
                            .register(meterRegistry)
                            .increment(usage.getPromptTokens());
                    Counter.builder("kukuvaia.llm.tokens.total")
                            .tag("provider", provider)
                            .tag("model", model)
                            .tag("type", "completion")
                            .register(meterRegistry)
                            .increment(usage.getCompletionTokens());

                    log.info("LLM response: promptTokens={}, completionTokens={}, totalTokens={}",
                            usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
                }
            }

            return response;
        } finally {
            clearMdc();
        }
    }

    private static void clearMdc() {
        MDC.remove("provider");
        MDC.remove("executionContext");
        MDC.remove("specialist");
    }

    private static String resolveModel(ChatClientRequest request) {
        var options = request.prompt().getOptions();
        if (options instanceof OpenAiChatOptions openAiOptions && openAiOptions.getModel() != null) {
            return openAiOptions.getModel();
        }
        return "unknown";
    }
}
