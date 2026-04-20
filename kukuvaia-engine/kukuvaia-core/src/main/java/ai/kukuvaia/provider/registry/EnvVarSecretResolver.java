package ai.kukuvaia.provider.registry;

import org.springframework.stereotype.Component;

/**
 * Resolves secret references by reading environment variables — fail-fast.
 *
 * <p>Contract per {@link SecretResolver#resolve(String)}: the reference is
 * an env-var name; if the variable is unset, blank, or the reference is
 * otherwise invalid, {@link SecretNotFoundException} is thrown. There is
 * intentionally no "fall back to treating the reference as a raw token"
 * branch — that silently hid misconfiguration in production (ops forgets
 * to set an env var and the code happily uses the variable NAME as the
 * API key, then outbound LLM calls fail with an opaque 401). The
 * {@code credentials.md} standard requires env-only secret loading, so
 * the safe behaviour is to refuse to start rather than limp along.
 *
 * <p>Trimming is tolerated so surrounding whitespace in a DB-stored
 * reference is not an operational hazard.
 */
@Component
public class EnvVarSecretResolver implements SecretResolver {

    private static final java.util.regex.Pattern ENV_VAR_NAME =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    @Override
    public String resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new SecretNotFoundException("(null or blank)");
        }

        String trimmed = reference.trim();

        if (!ENV_VAR_NAME.matcher(trimmed).matches()) {
            throw new SecretNotFoundException(trimmed);
        }

        String value = System.getenv(trimmed);
        if (value == null || value.isBlank()) {
            throw new SecretNotFoundException(trimmed);
        }
        return value;
    }
}
