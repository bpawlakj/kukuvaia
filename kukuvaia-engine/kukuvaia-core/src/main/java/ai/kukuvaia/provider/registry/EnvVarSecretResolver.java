package ai.kukuvaia.provider.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves secret references by reading environment variables.
 * If the reference matches an env var name, returns its value.
 * Otherwise treats the reference itself as the raw secret (e.g., a JWT token).
 */
@Component
public class EnvVarSecretResolver implements SecretResolver {

    private static final Logger log = LoggerFactory.getLogger(EnvVarSecretResolver.class);

    private static final java.util.regex.Pattern ENV_VAR_NAME = java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    @Override
    public String resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new SecretNotFoundException("(null or blank)");
        }

        String trimmed = reference.trim();

        // If it looks like an env var name, try to resolve it
        if (ENV_VAR_NAME.matcher(trimmed).matches()) {
            String value = System.getenv(trimmed);
            if (value != null && !value.isBlank()) {
                return value;
            }
            log.debug("'{}' looks like an env var name but is not set, using as raw value", trimmed);
        }

        // Use the value directly (raw token/key)
        return trimmed;
    }
}
