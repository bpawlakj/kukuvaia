package ai.kukuvaia.agent;

import java.util.List;

/**
 * Structured state captured during the planning discovery phase.
 * Mutable-by-replacement: each update produces a new instance.
 */
public record DiscoveryFacts(
        List<String> knownFacts,
        List<String> excludedOptions,
        List<String> remainingGaps,
        List<String> ambiguities
) {

    public DiscoveryFacts {
        knownFacts = knownFacts == null ? List.of() : List.copyOf(knownFacts);
        excludedOptions = excludedOptions == null ? List.of() : List.copyOf(excludedOptions);
        remainingGaps = remainingGaps == null ? List.of() : List.copyOf(remainingGaps);
        ambiguities = ambiguities == null ? List.of() : List.copyOf(ambiguities);
    }

    public static DiscoveryFacts empty() {
        return new DiscoveryFacts(List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return knownFacts.isEmpty() && excludedOptions.isEmpty()
                && remainingGaps.isEmpty() && ambiguities.isEmpty();
    }
}
