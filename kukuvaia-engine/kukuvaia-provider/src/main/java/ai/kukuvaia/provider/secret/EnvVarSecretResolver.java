package ai.kukuvaia.provider.secret;

import org.springframework.stereotype.Component;

/**
 * Dual-mode secret resolver.
 *
 * <p>If the reference matches the env-var identifier regex
 * ({@code ^[A-Za-z_][A-Za-z0-9_]*$}), it is treated as an env-var NAME
 * and resolved via {@link System#getenv(String)} (fail-fast when unset or
 * blank — see below). Otherwise the reference is returned verbatim as a
 * literal secret. JWTs and other tokens contain characters (".", "-",
 * "/") that never appear in a valid env-var name, so the two cases are
 * unambiguous by format.
 *
 * <p>The fail-fast branch for env-var names still matters: it prevents
 * the silent "use the variable NAME as the API key" failure mode (ops
 * forgets to export, outbound LLM calls 401 with no useful signal).
 *
 * <p>Literal-token mode is a stopgap so the admin UI can store a key
 * directly in the providers table. Plaintext storage is acceptable only
 * while an encryption-at-rest solution (pgcrypto / Jasypt) is pending;
 * see {@code .maister/docs/standards/security/credentials.md}.
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
            return trimmed;
        }

        String value = System.getenv(trimmed);
        if (value == null || value.isBlank()) {
            throw new SecretNotFoundException(trimmed);
        }
        return value;
    }
}
