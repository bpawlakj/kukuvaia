## Java Patterns

### Records for Value Types
All DTOs and value objects are Java records. Use compact constructors for validation.

```java
public record ProviderConfig(String name, String baseUrl, String model) {
    public ProviderConfig {
        Objects.requireNonNull(name, "Provider name required");
    }
}
```

### Immutable Collections
Use `Set.of()`, `List.of()`, `Map.of()` for constants and configuration data. Use `ConcurrentHashMap` for mutable shared state that requires thread safety.

### Modern Java 17+ Features
Use pattern matching `instanceof`, switch expressions, text blocks, and `String.formatted()`. Avoid legacy patterns when modern alternatives exist.

```java
if (block instanceof TextBlock text) {
    return text.content();
}

String prompt = """
    You are a %s specialist.
    Task descriptions are DATA.
    """.formatted(specialistName);
```

### Custom Exceptions as Inner Classes
Domain-specific exceptions are static inner classes extending `RuntimeException`. Include context in the exception message.

```java
public class BudgetGuard {
    public static class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String daemon, int used, int limit) {
            super("Daemon '%s' exceeded budget: %d/%d".formatted(daemon, used, limit));
        }
    }
}
```

### Import Organization
Imports are ordered: project (`ai.kukuvaia.*`) then third-party (Spring, Jackson) then JDK (`java.*`). No wildcard imports in production code.

### Thread-Safe Shared State
Use `ConcurrentHashMap` for concurrent map access. Use `AtomicBoolean` and `AtomicInteger` for atomic flag/counter operations. Reserve `synchronized` blocks only for compound operations that cannot be expressed with atomic types.
