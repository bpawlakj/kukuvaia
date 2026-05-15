package ai.kukuvaia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Security;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KukuvaiaApplication — startup-time JVM configuration")
class KukuvaiaApplicationTest {

    @Test
    @DisplayName("configureDnsCacheTtl — shortens positive TTL to 10s and disables negative cache")
    void configureDnsCacheTtl_setsExpectedSecurityProperties() {
        KukuvaiaApplication.configureDnsCacheTtl();

        assertThat(Security.getProperty("networkaddress.cache.ttl")).isEqualTo("10");
        assertThat(Security.getProperty("networkaddress.cache.negative.ttl")).isEqualTo("0");
    }
}
