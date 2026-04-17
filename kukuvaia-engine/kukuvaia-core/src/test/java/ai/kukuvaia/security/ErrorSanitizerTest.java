package ai.kukuvaia.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ErrorSanitizerTest {

    private ErrorSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new ErrorSanitizer();
    }

    // === Finding #11: Credentials in error messages ===

    @Test
    void sanitize_redactsJdbcConnectionStrings() {
        String msg = "Connection failed: jdbc:postgresql://db.internal:5432/kukuvaia?user=admin&password=secret";
        assertThat(sanitizer.sanitize(msg))
                .contains("[REDACTED]")
                .doesNotContain("jdbc:")
                .doesNotContain("password=secret");
    }

    @Test
    void sanitize_redactsMongoConnectionStrings() {
        String msg = "MongoDB error: mongodb+srv://admin:pass@cluster.mongodb.net/db";
        assertThat(sanitizer.sanitize(msg))
                .contains("[REDACTED]")
                .doesNotContain("mongodb+srv");
    }

    @Test
    void sanitize_redactsJwtTokens() {
        String msg = "Auth failed with token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.abc123";
        assertThat(sanitizer.sanitize(msg))
                .contains("[REDACTED]")
                .doesNotContain("eyJ");
    }

    @Test
    void sanitize_redactsGitHubOAuthTokens() {
        String msg = "Token expired: gho_ABC123def456";
        assertThat(sanitizer.sanitize(msg))
                .contains("[REDACTED]")
                .doesNotContain("gho_");
    }

    @Test
    void sanitize_redactsInternalPaths() {
        String msg = "File not found: /home/bartek/Projects/kukuvaia/config.yaml";
        assertThat(sanitizer.sanitize(msg))
                .contains("[REDACTED]")
                .doesNotContain("/home/bartek");
    }

    @Test
    void sanitize_redactsStackTraces() {
        String msg = "Error at ai.kukuvaia.agent.SubAgent.execute(SubAgent.java:42)";
        assertThat(sanitizer.sanitize(msg))
                .contains("[REDACTED]")
                .doesNotContain("SubAgent.java:42");
    }

    @Test
    void sanitize_preservesSafeMessages() {
        String msg = "Task failed: invalid entity ID format";
        assertThat(sanitizer.sanitize(msg)).isEqualTo(msg);
    }

    @Test
    void sanitize_truncatesLongMessages() {
        String msg = "Error: " + "x".repeat(1000);
        assertThat(sanitizer.sanitize(msg))
                .hasSizeLessThan(600)
                .endsWith("...[truncated]");
    }

    @Test
    void sanitize_handlesNull() {
        assertThat(sanitizer.sanitize(null)).isEqualTo("An internal error occurred");
    }

    @Test
    void sanitize_redactsCredentialsInUrls() {
        String msg = "Cannot connect to https://admin:s3cret@api.internal.com:8080/path";
        assertThat(sanitizer.sanitize(msg))
                .contains("[REDACTED]")
                .doesNotContain("admin:s3cret");
    }
}
