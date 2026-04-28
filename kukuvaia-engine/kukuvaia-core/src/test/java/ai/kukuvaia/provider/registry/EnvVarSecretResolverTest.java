package ai.kukuvaia.provider.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EnvVarSecretResolver — environment variable secret resolution")
class EnvVarSecretResolverTest {

    private EnvVarSecretResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new EnvVarSecretResolver();
    }

    @Test
    @DisplayName("resolve existing env var — returns value")
    void resolve_existingEnvVar_returnsValue() {
        // HOME is always set on Linux/macOS
        String result = resolver.resolve("HOME");
        assertThat(result).isNotBlank();
    }

    @Test
    @DisplayName("resolve non-existent env var — throws SecretNotFoundException")
    void resolve_nonExistent_throws() {
        assertThatThrownBy(() -> resolver.resolve("KUKUVAIA_NONEXISTENT_KEY_XYZ_12345"))
                .isInstanceOf(SecretResolver.SecretNotFoundException.class)
                .hasMessageContaining("KUKUVAIA_NONEXISTENT_KEY_XYZ_12345");
    }

    @Test
    @DisplayName("resolve null reference — throws SecretNotFoundException")
    void resolve_null_throws() {
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(SecretResolver.SecretNotFoundException.class);
    }

    @Test
    @DisplayName("resolve blank reference — throws SecretNotFoundException")
    void resolve_blank_throws() {
        assertThatThrownBy(() -> resolver.resolve("   "))
                .isInstanceOf(SecretResolver.SecretNotFoundException.class);
    }

    @Test
    @DisplayName("resolve with whitespace — trims and resolves")
    void resolve_withWhitespace_trims() {
        // " HOME " should resolve to HOME after trim
        String result = resolver.resolve(" HOME ");
        assertThat(result).isNotBlank();
    }

    @Test
    @DisplayName("resolve JWT-like literal — returns the reference verbatim")
    void resolve_literalJwt_returnsAsIs() {
        String jwt = "eyJhbGciOiJIUzUxMiJ9.eyJ0ZWFtIjoiYWJjIn0.signaturePart";
        assertThat(resolver.resolve(jwt)).isEqualTo(jwt);
    }

    @Test
    @DisplayName("resolve literal with punctuation — returns verbatim (not treated as env var name)")
    void resolve_literalWithPunctuation_returnsAsIs() {
        String literal = "sk-ant-1234.abcd/xyz";
        assertThat(resolver.resolve(literal)).isEqualTo(literal);
    }
}
