package ai.kukuvaia.provider.registry;

/**
 * Resolves secret references to actual values.
 * The default implementation reads from environment variables.
 * Future implementations: Vault, AWS Secrets Manager.
 */
public interface SecretResolver {

    /**
     * Resolve a secret reference to its actual value.
     *
     * @param reference the reference (e.g., env var name "SMARTGATE_API_KEY")
     * @return the resolved secret value
     * @throws SecretNotFoundException if the reference cannot be resolved
     */
    String resolve(String reference);

    class SecretNotFoundException extends RuntimeException {
        public SecretNotFoundException(String reference) {
            super("Secret reference '%s' could not be resolved. Ensure the environment variable is set.".formatted(reference));
        }
    }
}
