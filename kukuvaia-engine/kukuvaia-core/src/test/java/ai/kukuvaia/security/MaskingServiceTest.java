package ai.kukuvaia.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MaskingService — PII detection and masking")
class MaskingServiceTest {

    private MaskingService service;

    @BeforeEach
    void setUp() {
        service = new MaskingService(true, true, true, true, true, true);
    }

    @Nested
    @DisplayName("PESEL detection")
    class Pesel {

        @Test
        @DisplayName("masks 11-digit PESEL")
        void masks_pesel() {
            var ctx = service.mask("PESEL: 89012345678");
            assertThat(ctx.maskedText()).contains("[ID_1]");
            assertThat(ctx.maskedText()).doesNotContain("89012345678");
            assertThat(ctx.tokenToOriginal()).containsValue("89012345678");
        }

        @Test
        @DisplayName("does not mask 10-digit number")
        void ignores_10digits() {
            var ctx = service.mask("number: 1234567890");
            assertThat(ctx.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("Email detection")
    class Email {

        @Test
        @DisplayName("masks standard email")
        void masks_email() {
            var ctx = service.mask("send to jan@example.com");
            assertThat(ctx.maskedText()).contains("[EMAIL_");
            assertThat(ctx.maskedText()).doesNotContain("jan@example.com");
        }

        @Test
        @DisplayName("masks email with dots and plus")
        void masks_complexEmail() {
            var ctx = service.mask("user.name+tag@sub.domain.co.uk");
            assertThat(ctx.maskedText()).contains("[EMAIL_");
        }
    }

    @Nested
    @DisplayName("Phone detection")
    class Phone {

        @Test
        @DisplayName("masks Polish phone with +48")
        void masks_polishPhoneWithPrefix() {
            var ctx = service.mask("call +48 123 456 789");
            assertThat(ctx.maskedText()).contains("[PHONE_");
            assertThat(ctx.maskedText()).doesNotContain("123 456 789");
        }

        @Test
        @DisplayName("masks phone with dashes")
        void masks_phoneWithDashes() {
            var ctx = service.mask("tel: 123-456-789");
            assertThat(ctx.maskedText()).contains("[PHONE_");
        }
    }

    @Nested
    @DisplayName("Credit card detection")
    class CreditCard {

        @Test
        @DisplayName("masks card with dashes")
        void masks_cardWithDashes() {
            var ctx = service.mask("card: 4111-1111-1111-1111");
            assertThat(ctx.maskedText()).contains("[CC_");
            assertThat(ctx.maskedText()).doesNotContain("4111");
        }

        @Test
        @DisplayName("masks card with spaces")
        void masks_cardWithSpaces() {
            var ctx = service.mask("card: 4111 1111 1111 1111");
            assertThat(ctx.maskedText()).contains("[CC_");
        }
    }

    @Nested
    @DisplayName("API key detection")
    class ApiKey {

        @Test
        @DisplayName("masks sk-prefixed key")
        void masks_skKey() {
            var ctx = service.mask("key: sk-1234567890abcdefghijklmnop");
            assertThat(ctx.maskedText()).contains("[SECRET_");
            assertThat(ctx.maskedText()).doesNotContain("sk-1234567890");
        }

        @Test
        @DisplayName("masks api-prefixed key")
        void masks_apiKey() {
            var ctx = service.mask("api_key: api-abcdefghij1234567890");
            assertThat(ctx.maskedText()).contains("[SECRET_");
        }
    }

    @Nested
    @DisplayName("IBAN detection")
    class Iban {

        @Test
        @DisplayName("masks Polish IBAN")
        void masks_polishIban() {
            var ctx = service.mask("account: PL61 1090 1014 0000 0712 1981 2874");
            assertThat(ctx.maskedText()).contains("[IBAN_");
            assertThat(ctx.maskedText()).doesNotContain("1090");
        }
    }

    @Nested
    @DisplayName("Multiple PII values")
    class Multiple {

        @Test
        @DisplayName("masks multiple types in one message")
        void masks_multiplePii() {
            var ctx = service.mask("Jan Kowalski, email: jan@test.com, PESEL: 89012345678");
            assertThat(ctx.maskedText()).contains("[EMAIL_");
            assertThat(ctx.maskedText()).contains("[ID_");
            assertThat(ctx.tokenToOriginal()).hasSize(2);
        }

        @Test
        @DisplayName("tokens have incrementing IDs")
        void tokens_incrementingIds() {
            var ctx = service.mask("a@b.com and c@d.com");
            assertThat(ctx.tokenToOriginal().keySet())
                    .anyMatch(k -> k.contains("_1]"))
                    .anyMatch(k -> k.contains("_2]"));
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("no PII — returns unchanged, empty mapping")
        void noPii_unchanged() {
            var ctx = service.mask("how to write a Java loop?");
            assertThat(ctx.isEmpty()).isTrue();
            assertThat(ctx.maskedText()).isEqualTo("how to write a Java loop?");
        }

        @Test
        @DisplayName("null input — returns null, empty mapping")
        void nullInput_returnsNull() {
            var ctx = service.mask(null);
            assertThat(ctx.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("empty string — returns empty, empty mapping")
        void emptyInput_returnsEmpty() {
            var ctx = service.mask("");
            assertThat(ctx.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("Unmasking")
    class Unmask {

        @Test
        @DisplayName("unmask restores original values")
        void unmask_restoresOriginal() {
            var ctx = service.mask("email: jan@test.com");
            String unmasked = service.unmask("Your email is [EMAIL_1]", ctx.tokenToOriginal());
            assertThat(unmasked).isEqualTo("Your email is jan@test.com");
        }

        @Test
        @DisplayName("unmask with no tokens — returns unchanged")
        void unmask_noTokens_unchanged() {
            String result = service.unmask("hello world", java.util.Map.of());
            assertThat(result).isEqualTo("hello world");
        }

        @Test
        @DisplayName("unmask with null — returns input")
        void unmask_null_returnsInput() {
            String result = service.unmask("hello", null);
            assertThat(result).isEqualTo("hello");
        }
    }

    @Nested
    @DisplayName("Disabled rules")
    class DisabledRules {

        @Test
        @DisplayName("disabled email rule — email not masked")
        void disabledEmail_notMasked() {
            var svc = new MaskingService(true, false, true, true, true, true);
            var ctx = svc.mask("email: jan@test.com");
            assertThat(ctx.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("all rules disabled — nothing masked")
        void allDisabled_nothingMasked() {
            var svc = new MaskingService(false, false, false, false, false, false);
            var ctx = svc.mask("PESEL: 89012345678, email: jan@test.com");
            assertThat(ctx.isEmpty()).isTrue();
        }
    }
}
