package ai.kukuvaia.provider.copilot;

import ai.kukuvaia.provider.secret.EnvVarSecretResolver;
import ai.kukuvaia.provider.secret.SecretResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ai.kukuvaia.provider.service.ChatModelFactory;

/**
 * Composite {@link SecretResolver} that recognises Copilot references and delegates
 * everything else to {@link EnvVarSecretResolver}.
 *
 * <p>Reference shape: {@code copilot:default} (or any {@code copilot:*} label — the
 * suffix is reserved for future multi-account support). A provider row in the
 * {@code providers} table stores {@code api_key_ref = 'copilot:default'} and this
 * resolver swaps it for the live Copilot bearer at request time.
 *
 * <p>{@link ObjectProvider} avoids the bean cycle:
 * {@code ChatModelFactory ← SecretResolver ← CopilotTokenProvider}. The token
 * provider has no startup-time dependency here — it is only looked up when a
 * {@code copilot:*} ref is actually resolved.
 */
@Component
@Primary
public class CopilotSecretResolver implements SecretResolver {

    public static final String COPILOT_REF_PREFIX = "copilot:";

    private final ObjectProvider<CopilotTokenProvider> tokenProvider;
    private final EnvVarSecretResolver envResolver;

    public CopilotSecretResolver(
            ObjectProvider<CopilotTokenProvider> tokenProvider,
            EnvVarSecretResolver envResolver) {
        this.tokenProvider = tokenProvider;
        this.envResolver = envResolver;
    }

    @Override
    public String resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new SecretNotFoundException("(null or blank)");
        }
        if (reference.startsWith(COPILOT_REF_PREFIX)) {
            return tokenProvider.getObject().getCopilotToken()
                    .orElseThrow(() -> new SecretNotFoundException(
                            reference + " — not authenticated. Run /login github first."));
        }
        return envResolver.resolve(reference);
    }
}
