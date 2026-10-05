package com.archforge.gateway;

import com.archforge.gateway.config.SecurityConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two administrator slots must never be configurable by the same variable.
 *
 * <p>Regression guard for a defect with no error message anywhere: the gateway declared
 * {@code archforge.security.admin-user: ${ADMIN_USER:}} while
 * {@code SystemEnvironmentPropertySource} resolves that same key from
 * {@code ARCHFORGE_SECURITY_ADMIN_USER}, which is also the design-service
 * administrator's variable. Export both and the environment variable outranks the
 * placeholder, so {@code ADMIN_USER} silently stopped existing and
 * {@code archforge-admin} got 401 on every administrator request, with nothing logged.
 *
 * <p>Three things are asserted, not just the rename:
 * <ul>
 *   <li>the two slots resolve to disjoint properties from the packaged
 *       {@code application.yml}, with both administrator variables exported;</li>
 *   <li>both administrators end up usable, distinct accounts;</li>
 *   <li>the colliding property name, however it arrives, fails context refresh loudly
 *       rather than quietly authenticating a different administrator than was
 *       configured.</li>
 * </ul>
 */
class AdminSlotCollisionTest {

    private static final String COLLIDING = SecurityConfig.COLLIDING_ADMIN_PROPERTY;

    @Test
    void theShippedConfigurationBindsBothAdministratorsToDistinctProperties() throws Exception {
        // Reads the packaged application.yml the way the context does, with both
        // administrator variables exported, and resolves the slots through the same
        // placeholder chain that failed in production.
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "qaEnvironment", Map.of(
                        "ADMIN_USER", "archforge-admin",
                        "ADMIN_PASSWORD", "dev-admin-pass-2026",
                        "ARCHFORGE_SECURITY_DESIGN_ADMIN_USER", "design-service-admin",
                        "ARCHFORGE_SECURITY_DESIGN_ADMIN_PASSWORD", "dev-design-admin-pass-2026")));
        var yaml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml")).getFirst();
        assertThat(yaml).as("gateway application.yml must be on the test classpath").isNotNull();
        environment.getPropertySources().addLast(yaml);

        // The placeholder itself is what used to be silently lost.
        assertThat(environment.resolvePlaceholders("${ADMIN_USER:}")).isEqualTo("archforge-admin");
        assertThat(environment.getProperty("archforge.security.operator-admin-user"))
                .isEqualTo("archforge-admin");
        assertThat(environment.getProperty("archforge.security.operator-admin-password"))
                .isEqualTo("dev-admin-pass-2026");
        assertThat(environment.getProperty("archforge.security.design-admin-user"))
                .isEqualTo("design-service-admin");
        // The gateway's own slot is not reachable through the design variable's name.
        assertThat(environment.getProperty("archforge.security.operator-admin-user"))
                .isNotEqualTo(environment.getProperty("archforge.security.design-admin-user"));
        assertThat(environment.getProperty("archforge.security.operator-admin-password"))
                .isNotEqualTo(environment.getProperty("archforge.security.design-admin-password"));
    }

    @Test
    void relaxedBindingOfTheDesignAdminVariableCannotReachTheGatewayAdminSlot() {
        // The gateway slot's only relaxed-binding name is
        // ARCHFORGE_SECURITY_OPERATOR_ADMIN_USER, which is not the design-service
        // administrator's variable. Fails if either slot is renamed towards the other.
        for (String gatewaySlot : new String[]{
                "archforge.security.operator-admin-user", "archforge.security.operator-admin-password"}) {
            for (String designSlot : new String[]{
                    "archforge.security.design-admin-user", "archforge.security.design-admin-password"}) {
                assertThat(relaxedNameOf(gatewaySlot)).isNotEqualTo(relaxedNameOf(designSlot));
            }
        }
        assertThat(relaxedNameOf("archforge.security.operator-admin-user"))
                .isEqualTo("ARCHFORGE_SECURITY_OPERATOR_ADMIN_USER");
        assertThat(relaxedNameOf("archforge.security.operator-admin-user"))
                .isNotEqualTo("ARCHFORGE_SECURITY_ADMIN_USER");
        assertThat(relaxedNameOf("archforge.security.design-admin-user"))
                .isNotEqualTo("ARCHFORGE_SECURITY_ADMIN_USER");
    }

    @Test
    void bothAdministratorsAreUsableDistinctAccountsWhenBothVariablesAreExported() {
        var config = new SecurityConfig();
        var encoder = config.gatewayPasswordEncoder();
        var users = config.gatewayUsers(encoder, "", "", "", "",
                "archforge-admin", "dev-admin-pass-2026",
                "design-service-admin", "dev-design-admin-pass-2026");
        var operator = users.findByUsername("archforge-admin").block();
        assertThat(operator).isNotNull();
        assertThat(encoder.matches("dev-admin-pass-2026", operator.getPassword())).isTrue();
        assertThat(operator.getAuthorities()).extracting(a -> a.getAuthority()).contains("ROLE_ADMIN");
        var design = users.findByUsername("design-service-admin").block();
        assertThat(design).isNotNull();
        assertThat(encoder.matches("dev-design-admin-pass-2026", design.getPassword())).isTrue();
        assertThat(design.getAuthorities()).extracting(a -> a.getAuthority()).contains("ROLE_ADMIN");
    }

    @Test
    void theRealRelaxedBindingOfTheDesignVariableTriggersTheStartupFailure() {
        // Reproduces the mechanism: a property source named systemEnvironment is what
        // gives Spring its underscore and case-insensitive lookup.
        var environment = new StandardEnvironment();
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("ARCHFORGE_SECURITY_ADMIN_USER", "design-service-admin",
                               "ADMIN_USER", "archforge-admin")));
        assertThat(environment.getProperty(COLLIDING)).isEqualTo("design-service-admin");
        assertThatThrownBy(() -> new SecurityConfig().legacyAdminSlotGuard(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(COLLIDING);
    }

    @Test
    void contextRefreshFailsLoudlyWhenTheCollidingNameIsConfigured() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environmentWith(COLLIDING, "archforge-admin"));
            context.registerBean(SecurityConfig.LegacyAdminSlotGuard.class,
                    () -> new SecurityConfig().legacyAdminSlotGuard(context.getEnvironment()));
            assertThatThrownBy(context::refresh)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining(COLLIDING);
        }
    }

    @Test
    void anEmptyCollidingValueStillCountsAsConfigured() {
        assertThatThrownBy(() -> new SecurityConfig().legacyAdminSlotGuard(environmentWith(COLLIDING, "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_USER");
    }

    @Test
    void aNormalOperatorEnvironmentPassesTheGuardAndKeepsTheAdminCredential() {
        var environment = environmentWith("archforge.security.operator-admin-user", "archforge-admin");
        assertThat(new SecurityConfig().legacyAdminSlotGuard(environment)).isNotNull();

        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            var config = new SecurityConfig();
            var encoder = config.gatewayPasswordEncoder();
            context.registerBean(ReactiveUserDetailsService.class,
                    () -> config.gatewayUsers(encoder, "", "", "", "",
                            "archforge-admin", "dev-admin-pass-2026", "", ""));
            context.refresh();
            var operator = context.getBean(ReactiveUserDetailsService.class)
                    .findByUsername("archforge-admin").block();
            assertThat(operator).isNotNull();
            assertThat(encoder.matches("dev-admin-pass-2026", operator.getPassword())).isTrue();
            assertThat(operator.getAuthorities()).extracting(a -> a.getAuthority()).contains("ROLE_ADMIN");
        }
    }

    /** {@code ARCHFORGE_SECURITY_ADMIN_USER} is the name that collides with the old slot. */
    private static String relaxedNameOf(String property) {
        return property.toUpperCase().replace('.', '_').replace('-', '_');
    }

    private ConfigurableEnvironment environmentWith(String key, String value) {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("qa", Map.of(key, value)));
        return environment;
    }
}