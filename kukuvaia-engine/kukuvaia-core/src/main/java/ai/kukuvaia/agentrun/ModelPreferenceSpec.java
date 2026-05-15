package ai.kukuvaia.agentrun;

/**
 * Optional per-call model selection and escalation hint.
 *
 * <p>Precedence (see P23 §Design/6): explicit primary > taskClass routing (P19) > ChatClient default.
 * The explicit primary always wins when set; routing integration is a follow-up.
 *
 * @param primary           target model id for first pass; null falls through to default
 * @param escalateTo        optional model id for re-run when escalationTrigger evaluates true
 * @param escalationTrigger SpEL expression evaluated against parsed JSON output + run metadata.
 *                          Examples: {@code output.needs_escalation == true},
 *                          {@code output.severity in {'high','critical'}}
 */
public record ModelPreferenceSpec(
        String primary,
        String escalateTo,
        String escalationTrigger) {

    public boolean hasEscalation() {
        return escalateTo != null && !escalateTo.isBlank()
                && escalationTrigger != null && !escalationTrigger.isBlank();
    }
}
