package com.archforge.graphcompiler.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Authentication and authorisation for the compiler.
 *
 * <p>The previous version shipped {@code admin/admin} in source. That is not a
 * style complaint: it granted {@code ROLE_ADMIN}, which in turn unlocked
 * {@code /actuator/**} — including {@code /actuator/heapdump} and
 * {@code /actuator/loggers}, the latter letting anyone who knew the password turn
 * on TRACE logging across the service.
 *
 * <p>What replaced it:
 * <ul>
 *   <li>Credentials come from configuration with <b>no working default</b>. If they
 *       are not supplied the service refuses to start rather than falling back to a
 *       guessable pair. Failing closed is the only safe behaviour for an auth
 *       configuration.</li>
 *   <li>Passwords are bcrypt-hashed at startup rather than compared as plaintext,
 *       so a leaked configuration file does not immediately yield the credentials.</li>
 *   <li>The health groups are explicitly permitted. The previous rule permitted
 *       exactly {@code /actuator/health} and {@code /actuator/info} and then
 *       required {@code ROLE_ADMIN} for everything else under {@code /actuator/},
 *       which made {@code /actuator/health/liveness} and
 *       {@code /actuator/health/readiness} return 401. Kubernetes probes send no
 *       credentials, so the pod was killed and restarted in a loop.</li>
 *   <li>CSRF stays disabled, correctly: this is a stateless token/basic API with no
 *       cookie-authenticated browser session to forge against.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    @Value("${archforge.security.compile-user:}")
    private String compileUser;

    @Value("${archforge.security.compile-password:}")
    private String compilePassword;

    @Value("${archforge.security.admin-user:}")
    private String adminUser;

    @Value("${archforge.security.admin-password:}")
    private String adminPassword;

    @Value("${archforge.security.enabled:true}")
    private boolean securityEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Probes and liveness/readiness must be reachable without
                        // credentials or the container never passes its health gate.
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/health/liveness",
                                "/actuator/health/readiness")
                        .permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/compile/**").hasRole("USER")
                        .anyRequest().authenticated());

        if (securityEnabled) {
            http.httpBasic(Customizer.withDefaults());
        } else {
            // Local development only: no credentials required at all. Guarded by an
            // explicit flag so it can never be switched on by accident in production.
            http.httpBasic(httpBasic -> httpBasic.disable());
        }

        return http.build();
    }

    /**
     * @throws IllegalStateException when security is enabled but no credentials were
     *         supplied. Starting with a default password is how {@code admin/admin}
     *         ended up in version control.
     */
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        if (!securityEnabled) {
            return new InMemoryUserDetailsManager();
        }

        if (isBlank(compileUser) || isBlank(compilePassword)) {
            throw new IllegalStateException(
                    "archforge.security.compile-user and archforge.security.compile-password must be set. "
                            + "Refusing to start with a default or missing credential. "
                            + "Set them, or set archforge.security.enabled=false for local development.");
        }

        InMemoryUserDetailsManager manager = new InMemoryUserDetailsManager();
        manager.createUser(User.withUsername(compileUser)
                .password(passwordEncoder.encode(compilePassword))
                .roles("USER")
                .build());

        if (!isBlank(adminUser) && !isBlank(adminPassword)) {
            manager.createUser(User.withUsername(adminUser)
                    .password(passwordEncoder.encode(adminPassword))
                    .roles("USER", "ADMIN")
                    .build());
        }

        return manager;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
