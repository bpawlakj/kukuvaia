package ai.kukuvaia.memory.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Memory module configuration: chat memory + local embedding model.
 * Uses all-MiniLM-L6-v2 via ONNX Runtime on CPU — no external API, no SmartGate.
 */
@Configuration
public class MemoryModuleConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryModuleConfig.class);

    /**
     * Sliding window of last N chat messages included in every LLM request.
     *
     * Sized for modern LLMs — Mistral Small 2603 has 262K context, Claude
     * Sonnet 4.5 has 200K, Elephant Alpha 256K. At typical ~300 tokens per
     * message, 100 messages ≈ 30K tokens — well within all supported models
     * while still providing meaningful conversation continuity.
     *
     * Override via env var or application property:
     *   KUKUVAIA_CHAT_MEMORY_MAX_MESSAGES=200
     *   kukuvaia.chat.memory.max-messages=200
     */
    @Value("${kukuvaia.chat.memory.max-messages:100}")
    private int maxMessages;

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        log.info("ChatMemory initialized with maxMessages={}", maxMessages);
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(maxMessages)
                .build();
    }

    /**
     * Single-thread executor for background memory extraction.
     * Ensures extractions run serially (no LLM flooding) and never block the user.
     */
    @Bean
    Executor memoryExtractionExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "memory-extraction");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    EmbeddingModel transformersEmbeddingModel() {
        // Hide GPU from ONNX Runtime to prevent CUDA errors on machines with partial GPU support
        System.setProperty("CUDA_VISIBLE_DEVICES", "");
        log.info("Initializing local embedding model: all-MiniLM-L6-v2 (ONNX, 384 dims, CPU-only)");
        var model = new TransformersEmbeddingModel();
        model.setGpuDeviceId(-1);
        return model;
    }
}
