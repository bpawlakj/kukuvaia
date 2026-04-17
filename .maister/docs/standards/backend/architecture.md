## Backend Architecture

### Base Package ai.kukuvaia
All Java classes live under the `ai.kukuvaia` base package. Sub-packages follow the domain structure.

### Domain Package Structure
Packages are organized by domain concern:
- `ai.kukuvaia.security` -- guards, filters, sanitizers
- `ai.kukuvaia.agent.daemon` -- daemon tasks, budget guards, scheduling
- `ai.kukuvaia.agent.subagent` -- sub-agent delegation, isolation
- `ai.kukuvaia.provider` -- LLM provider routing, registry, config
- `ai.kukuvaia.api.webhook` -- webhook endpoints, payload processing

### Spring Boot 3.x + Spring AI 1.x
The server requires Spring Boot 3.x with Spring AI 1.x. This is the mandatory framework stack for the backend.

### Dual Provider Routing
Interactive requests route to Copilot (GitHub OAuth); daemon tasks route to SmartGate (JWT). Fallback chain: explicit provider > specialist default > context-based > global default.

### Spring AI Advisor Pattern
Custom advisors extend `BaseAdvisor` with ordered `before()` and `after()` methods. Ordering is controlled via `getOrder()` to define the advisor chain execution sequence.

### kukuvaia.* Config Prefix
All application configuration uses the `kukuvaia.*` prefix in `@Value` annotations. Every `@Value` must include a default value.

```java
@Value("${kukuvaia.daemon.budget.daily:100000}")
private final int dailyBudget;

@Value("${kukuvaia.daemon.budget.alert-threshold:0.8}")
private final double alertThreshold;
```
