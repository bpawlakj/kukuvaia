package ai.kukuvaia.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects and masks PII in text using configurable regex patterns.
 * Each detected value is replaced with a token like {@code [EMAIL_1]}, {@code [ID_2]}.
 * Returns a {@link MaskingContext} with the mapping for later unmasking.
 */
@Component
public class MaskingService {

    private static final Logger log = LoggerFactory.getLogger(MaskingService.class);

    private final List<MaskingRule> rules;

    public MaskingService(
            @Value("${kukuvaia.masking.rules.pesel:true}") boolean pesel,
            @Value("${kukuvaia.masking.rules.email:true}") boolean email,
            @Value("${kukuvaia.masking.rules.phone:true}") boolean phone,
            @Value("${kukuvaia.masking.rules.credit-card:true}") boolean creditCard,
            @Value("${kukuvaia.masking.rules.api-keys:true}") boolean apiKeys,
            @Value("${kukuvaia.masking.rules.iban:true}") boolean iban
    ) {
        // Order: longest/most specific patterns first to prevent partial matches
        var active = new java.util.ArrayList<MaskingRule>();
        if (iban) active.add(IBAN_RULE);
        if (creditCard) active.add(CREDIT_CARD_RULE);
        if (apiKeys) active.add(API_KEY_RULE);
        if (pesel) active.add(PESEL_RULE);
        if (email) active.add(EMAIL_RULE);
        if (phone) active.add(PHONE_RULE);
        this.rules = List.copyOf(active);
        log.info("MaskingService initialized with {} rules: {}", rules.size(),
                rules.stream().map(MaskingRule::category).toList());
    }

    /**
     * Detect and mask PII in the given text.
     *
     * @return context with masked text and token→original mapping, or unchanged text if nothing detected
     */
    public MaskingContext mask(String text) {
        if (text == null || text.isBlank()) {
            return new MaskingContext(text, Map.of());
        }

        Map<String, String> tokenToOriginal = new LinkedHashMap<>();
        String masked = text;
        int counter = 1;

        for (MaskingRule rule : rules) {
            Matcher matcher = rule.pattern().matcher(masked);
            // Collect all matches first to avoid concurrent modification
            var matches = new java.util.ArrayList<String>();
            while (matcher.find()) {
                matches.add(matcher.group());
            }
            for (String match : matches) {
                // Skip if already masked (contains bracket tokens)
                if (match.startsWith("[") && match.endsWith("]")) continue;

                String token = "[%s_%d]".formatted(rule.category(), counter++);
                tokenToOriginal.put(token, match);
                masked = masked.replace(match, token);
            }
        }

        if (!tokenToOriginal.isEmpty()) {
            log.debug("Masked {} PII values in text", tokenToOriginal.size());
        }

        return new MaskingContext(masked, Map.copyOf(tokenToOriginal));
    }

    /**
     * Restore original values from tokens in the text.
     */
    public String unmask(String text, Map<String, String> tokenToOriginal) {
        if (text == null || tokenToOriginal == null || tokenToOriginal.isEmpty()) {
            return text;
        }
        String result = text;
        for (var entry : tokenToOriginal.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    // --- Rules ---

    static final MaskingRule PESEL_RULE = new MaskingRule(
            Pattern.compile("\\b\\d{11}\\b"), "ID");

    static final MaskingRule EMAIL_RULE = new MaskingRule(
            Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}"), "EMAIL");

    static final MaskingRule PHONE_RULE = new MaskingRule(
            Pattern.compile("\\b(?:\\+48\\s?)?\\d{3}[\\s-]\\d{3}[\\s-]\\d{3}\\b"), "PHONE");

    static final MaskingRule CREDIT_CARD_RULE = new MaskingRule(
            Pattern.compile("\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b"), "CC");

    static final MaskingRule API_KEY_RULE = new MaskingRule(
            Pattern.compile("(?:sk|pk|api)[_-][A-Za-z0-9]{20,}"), "SECRET");

    static final MaskingRule IBAN_RULE = new MaskingRule(
            Pattern.compile("\\b[A-Z]{2}\\d{2}\\s?\\d{4}\\s?\\d{4}\\s?\\d{4}\\s?\\d{4}\\s?\\d{0,4}\\b"), "IBAN");

    record MaskingRule(Pattern pattern, String category) {}
}
