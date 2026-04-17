## Dependency Injection

### Constructor Injection Only
All dependencies are injected via constructor parameters. Fields are declared `final`. No `@Autowired` on fields. This ensures immutability and makes dependencies explicit.

```java
@Service
public class ProviderRouter {
    private final ProviderRegistry registry;
    private final ProviderConfig config;

    public ProviderRouter(ProviderRegistry registry, ProviderConfig config) {
        this.registry = registry;
        this.config = config;
    }
}
```

### Stereotype Annotations
Use the correct Spring stereotype for each class role:
- `@Component` -- general-purpose beans
- `@Service` -- orchestrators and business logic
- `@Configuration` -- configuration and bean definitions
- `@RestController` -- HTTP API endpoints
- `@RestControllerAdvice` -- global exception handlers
