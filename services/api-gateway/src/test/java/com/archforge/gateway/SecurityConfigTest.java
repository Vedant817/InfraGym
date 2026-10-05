package com.archforge.gateway;

import com.archforge.gateway.config.SecurityConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigTest {
    private final SecurityConfig config = new SecurityConfig();

    @Test
    void emptyConfigurationNeverCreatesADefaultAccount() {
        var users = config.gatewayUsers(config.gatewayPasswordEncoder(), "", "", "", "", "", "", "", "");
        assertThat(users.findByUsername("admin").block()).isNull();
        assertThat(users.findByUsername("user").block()).isNull();
    }

    @Test
    void incompleteAndConflictingDownstreamCredentialsAreRejected() {
        var encoder = config.gatewayPasswordEncoder();
        assertThatThrownBy(() -> config.gatewayUsers(encoder, "compiler", "", "", "", "", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.gatewayUsers(encoder,
                "same-user", "password-one", "same-user", "password-two", "", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.gatewayUsers(encoder,
                "Alice", "password-one", "alice", "password-two", "", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchingDownstreamAccountsShareOneIdentityAndExplicitAdminRole() {
        var encoder = config.gatewayPasswordEncoder();
        var users = config.gatewayUsers(encoder, "shared", "test-password", "shared", "test-password",
                "shared", "test-password", "", "");
        var user = users.findByUsername("shared").block();
        assertThat(user.getAuthorities()).extracting(a -> a.getAuthority())
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
        assertThat(user.getPassword()).isNotEqualTo("test-password");
        assertThat(encoder.matches("test-password", user.getPassword())).isTrue();
    }

    @Test
    void corsCannotBeReconfiguredToAWildcardOrNonOrigin() {
        for (String origin : new String[]{"*", "https://*.example.com", "https://example.com/path", "file:///tmp"}) {
            assertThatThrownBy(() -> config.gatewayCors(origin)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(config.gatewayCors("https://app.example.com, http://localhost:3000")).isNotNull();
    }

    @Test
    void caseAliasesKeepTheSamePrincipalAndExplicitAdminRole() {
        var users = config.gatewayUsers(config.gatewayPasswordEncoder(),
                "Alice", "test-password", "alice", "test-password", "ALICE", "test-password", "", "");
        var user = users.findByUsername("aLiCe").block();
        assertThat(user.getUsername()).isEqualTo("Alice");
        assertThat(user.getAuthorities()).extracting(a -> a.getAuthority())
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }
}
