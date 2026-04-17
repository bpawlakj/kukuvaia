package ai.kukuvaia.memory.extraction;

import ai.kukuvaia.memory.embedding.EmbeddingService;
import ai.kukuvaia.memory.model.MemoryEntry;
import ai.kukuvaia.memory.repository.SessionRepository;
import ai.kukuvaia.memory.repository.SmartMemoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Automatic memory extraction from conversations.
 * After each chat turn, analyzes new messages since the last extraction cursor
 * and distills facts worth remembering into the memories table.
 *
 * Uses the same ChatModel (LLM) as the main agent but with a focused extraction prompt.
 * Runs asynchronously — never blocks the user's chat response.
 */
@Service
public class MemoryExtractionService {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionService.class);
    private static final int MIN_NEW_MESSAGES = 2; // Don't extract from a single exchange

    private final ChatModel chatModel;
    private final ChatMemoryRepository chatMemoryRepository;
    private final SmartMemoryRepository memoryRepository;
    private final SessionRepository sessionRepository;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    public MemoryExtractionService(ChatModel chatModel,
                                    ChatMemoryRepository chatMemoryRepository,
                                    SmartMemoryRepository memoryRepository,
                                    SessionRepository sessionRepository,
                                    EmbeddingService embeddingService) {
        this.chatModel = chatModel;
        this.chatMemoryRepository = chatMemoryRepository;
        this.memoryRepository = memoryRepository;
        this.sessionRepository = sessionRepository;
        this.embeddingService = embeddingService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Extract memories from new messages in a session.
     * Called asynchronously after each chat turn.
     */
    public void extract(String userId, String sessionId) {
        try {
            // Get conversation and cursor
            var allMessages = chatMemoryRepository.findByConversationId(sessionId);
            int cursor = sessionRepository.getExtractionCursor(sessionId);
            int total = allMessages.size();

            if (total - cursor < MIN_NEW_MESSAGES) {
                log.debug("Extraction skipped: only {} new messages (cursor={}, total={})",
                        total - cursor, cursor, total);
                return;
            }

            // Get new messages since cursor
            var newMessages = allMessages.subList(Math.max(0, cursor), total);
            String transcript = formatTranscript(newMessages);

            // Get existing memories for dedup
            var existing = memoryRepository.findByUser(userId);
            String existingJson = formatExistingMemories(existing);

            // Build extraction prompt
            String prompt = buildExtractionPrompt(transcript, existingJson);

            // Call LLM for extraction
            log.info("Memory extraction: sessionId={}, newMessages={}, existingMemories={}",
                    sessionId, newMessages.size(), existing.size());

            var response = chatModel.call(new Prompt(prompt));
            String content = response.getResult().getOutput().getText();

            // Parse extracted facts
            var facts = parseExtractedFacts(content);

            if (facts.isEmpty()) {
                log.debug("No new facts extracted from session {}", sessionId);
            } else {
                // Save each extracted fact
                for (var fact : facts) {
                    saveFact(userId, sessionId, fact);
                }
                log.info("Extracted {} memories from session {} for user {}", facts.size(), sessionId, userId);
            }

            // Advance cursor
            sessionRepository.updateExtractionCursor(sessionId, total);

        } catch (Exception e) {
            // Never crash — log and skip. Cursor stays at old position, retry next turn.
            log.warn("Memory extraction failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    private String formatTranscript(List<Message> messages) {
        var sb = new StringBuilder();
        for (var msg : messages) {
            if (msg instanceof UserMessage um) {
                sb.append("USER: ").append(um.getText()).append("\n\n");
            } else if (msg instanceof AssistantMessage am) {
                String text = am.getText();
                if (text != null && text.length() > 500) {
                    text = text.substring(0, 500) + "... [truncated]";
                }
                sb.append("ASSISTANT: ").append(text).append("\n\n");
            }
        }
        return sb.toString();
    }

    private String formatExistingMemories(List<MemoryEntry> memories) {
        if (memories.isEmpty()) return "[]";
        var sb = new StringBuilder("[");
        for (int i = 0; i < memories.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"").append(escapeJson(memories.get(i).name()))
              .append("\",\"description\":\"").append(escapeJson(memories.get(i).description()))
              .append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String buildExtractionPrompt(String transcript, String existingMemories) {
        return """
                You are a memory extraction system. Analyze the conversation and extract facts worth remembering for future sessions.

                ## Categories (extract ONLY these)
                - **user**: Personal facts, preferences, expertise, role, communication style
                - **project**: Tech stack, goals, team structure, architecture decisions
                - **feedback**: Corrections or confirmations about assistant behavior, style preferences
                - **reference**: Important URLs, docs, external systems, API endpoints

                ## Memory Types
                - **semantic**: General knowledge/facts (most common)
                - **procedural**: How-to knowledge, workflows, processes
                - **episodic**: Specific events or accomplishments worth noting

                ## DO NOT extract
                - Transient task details (current debugging specifics)
                - Code snippets or git history (derivable from code)
                - Anything already in the existing memories below
                - Trivial facts, greetings, or small talk

                ## Existing memories (for deduplication)
                %s

                ## Conversation
                %s

                ## Output
                Return ONLY a JSON array. If nothing worth remembering, return [].
                Each element: {"category":"user|project|feedback|reference","memory_type":"semantic|procedural|episodic","name":"unique-kebab-case-key","description":"Brief one-line summary","content":"Full fact to remember"}
                """.formatted(existingMemories, transcript);
    }

    private List<Map<String, String>> parseExtractedFacts(String llmResponse) {
        try {
            // Strip markdown code fences if present
            String json = llmResponse.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
            }
            if (!json.startsWith("[")) {
                log.debug("Extraction returned non-JSON: {}", json.substring(0, Math.min(100, json.length())));
                return List.of();
            }
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse extraction JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private void saveFact(String userId, String sessionId, Map<String, String> fact) {
        String category = fact.getOrDefault("category", "project");
        String memoryType = fact.getOrDefault("memory_type", "semantic");
        String name = fact.getOrDefault("name", "extracted-" + System.currentTimeMillis());
        String description = fact.getOrDefault("description", "");
        String content = fact.getOrDefault("content", "");

        if (content.isBlank()) return;

        try {
            MemoryEntry entry = memoryRepository.save(userId, category, name, description, content,
                    memoryType, 1.0, sessionId);
            embeddingService.embedAndStore(entry.id(), description, content);
            log.debug("Saved extracted memory: {} ({})", name, category);
        } catch (Exception e) {
            log.warn("Failed to save extracted memory '{}': {}", name, e.getMessage());
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
