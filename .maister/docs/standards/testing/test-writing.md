## Test Writing

### Test Behavior
Focus on what code does, not how it does it, to allow safe refactoring.

### Clear Names
Use descriptive names explaining what's tested and expected (`shouldReturnErrorWhenUserNotFound`).

### Mock External Dependencies
Isolate tests by mocking databases, APIs, and external services.

### Fast Execution
Keep unit tests fast (milliseconds) so developers run them frequently.

### Risk-Based Testing
Prioritize testing based on business criticality and likelihood of bugs.

### Balance Coverage and Velocity
Adjust test coverage based on project needs and team workflow.

### Critical Path Focus
Ensure core user workflows and critical business logic are well-tested.

### Appropriate Depth
Match edge case testing to the risk profile of the code.

### JUnit 5 + AssertJ
Use JUnit 5 as the test framework with AssertJ for fluent assertions (`assertThat`). No Hamcrest matchers. Use `assertThatThrownBy` for exception testing.

```java
assertThat(result.name()).isEqualTo("test");
assertThatThrownBy(() -> guard.validate(null))
    .isInstanceOf(IllegalArgumentException.class);
```

### Test Naming Convention
Test methods use `methodName_scenario_expectedBehavior` with underscore separation.

```java
void resolveProvider_explicitProvider_returnsExplicit() { }
void validate_nullInput_throwsIllegalArgument() { }
```

### Package-Private Test Classes
Test classes have no `public` modifier. Class name matches the production class with a `Test` suffix (e.g., `ProviderRouterTest` for `ProviderRouter`).

### @BeforeEach with Real Objects
Use `@BeforeEach setUp()` for test initialization. Instantiate real objects via constructors. Prefer real objects over mocks when feasible.

### 80%+ Coverage for Security-Critical Code
Security-critical classes (guards, filters, sanitizers) require 80% or higher test coverage as a priority target.

### Static Import for AssertJ
Always use static imports for AssertJ assertions.

```java
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```
