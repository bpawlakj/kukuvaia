package ai.kukuvaia.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sanitizes external payloads (webhooks, PG NOTIFY) before embedding in LLM prompts.
 * Covers: Finding #5 (webhook payload prompt injection), Finding #12 (sub-agent prompt injection).
 *
 * Strategy: extract only known structured fields, discard everything else.
 * Never embed raw external text in LLM prompts.
 */
@Component
public class PayloadSanitizer {

    private static final Logger log = LoggerFactory.getLogger(PayloadSanitizer.class);
    private static final int MAX_FIELD_LENGTH = 500;
    private static final int MAX_TOTAL_LENGTH = 2000;

    // Patterns that look like prompt injection attempts
    private static final Pattern INJECTION_PATTERN = Pattern.compile(
            "(?i)(ignore|forget|disregard)\\s+(previous|above|all)\\s+(instructions?|rules?|context)" +
            "|(?i)(you are now|act as|pretend to be|new instructions?:)" +
            "|(?i)(system:\\s*|<\\|?system\\|?>)" +
            "|(?i)(do not follow|override|bypass)\\s+(the|your|any)\\s+(rules?|instructions?|restrictions?)"
    );

    private final ObjectMapper objectMapper;

    public PayloadSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Extract only allowed fields from a JSON payload.
     * Unknown fields are silently dropped.
     */
    public Map<String, String> extractFields(String rawPayload, Set<String> allowedFields) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            for (String field : allowedFields) {
                JsonNode node = root.path(field);
                if (!node.isMissingNode() && !node.isNull()) {
                    String value = sanitizeValue(node.asText());
                    if (value != null) {
                        result.put(field, value);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse payload as JSON, treating as opaque: {}",
                    e.getMessage());
        }
        return result;
    }

    /**
     * Sanitize a single string value for safe embedding in LLM context.
     * Returns null if the value appears malicious.
     */
    public String sanitizeValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // Truncate
        String value = raw.length() > MAX_FIELD_LENGTH
                ? raw.substring(0, MAX_FIELD_LENGTH) + "...[truncated]"
                : raw;
        // Strip control characters (except newline, tab)
        value = value.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        // Check for injection patterns
        if (INJECTION_PATTERN.matcher(value).find()) {
            log.warn("Potential prompt injection detected in payload value, rejecting");
            return "[REDACTED: suspicious content]";
        }
        return value;
    }

    /**
     * Build a safe prompt fragment from extracted fields.
     * Output is a structured key: value block, not raw text.
     */
    public String toPromptFragment(Map<String, String> fields) {
        if (fields.isEmpty()) {
            return "(no data extracted from event)";
        }
        var sb = new StringBuilder();
        int totalLength = 0;
        for (var entry : fields.entrySet()) {
            String line = entry.getKey() + ": " + entry.getValue();
            if (totalLength + line.length() > MAX_TOTAL_LENGTH) {
                sb.append("...[remaining fields truncated for safety]\n");
                break;
            }
            sb.append(line).append('\n');
            totalLength += line.length();
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Sanitize a PG NOTIFY payload (limited to 8000 bytes by PostgreSQL).
     */
    public String sanitizePgNotify(String payload, Set<String> allowedFields) {
        if (payload == null || payload.isBlank()) {
            return "(empty notification)";
        }
        // PG NOTIFY can be plain text or JSON
        if (payload.trim().startsWith("{")) {
            return toPromptFragment(extractFields(payload, allowedFields));
        }
        // Plain text — sanitize as single value
        String sanitized = sanitizeValue(payload);
        return sanitized != null ? sanitized : "(notification content redacted)";
    }
}
