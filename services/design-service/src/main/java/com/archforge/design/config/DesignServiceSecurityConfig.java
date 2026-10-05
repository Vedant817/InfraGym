package com.archforge.design.config;

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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Authentication for the system of record.
 *
 * <p>Same shape as the compiler's, and for the same reason: this is the service that holds a
 * user's designs and every measurement taken of them, so a missing credential here is a data
 * breach rather than a nuisance. Credentials come from configuration with <b>no working
 * default</b> -- the service refuses to start rather than falling back to something
 * guessable. Failing closed is the only safe behaviour for an auth configuration, and
 * {@code admin/admin} in version control is how that rule gets broken.
 *
 * <p>Ownership is enforced in the queries rather than here ({@code findByIdAndUserId} and
 * friends). That is the stronger placement: an authorization check that lives in a controller
 * can be forgotten on the next handler, whereas one that is part of the repository signature
 * cannot be called without passing the user.
 *
 * <p>Health and the liveness/readiness probes are permitted explicitly. Kubernetes sends no
 * credentials, so requiring them means the pod is killed and restarted in a loop while the
 * logs say the probes were unauthorized.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class DesignServiceSecurityConfig {

    @Value("${archforge.security.user:}")
    private String user;

    @Value("${archforge.security.password:}")
    private String password;

    @Value("${archforge.security.admin-user:}")
    private String adminUser;

    @Value("${archforge.security.admin-password:}")
    private String adminPassword;

    @Value("${archforge.security.enabled:true}")
    private boolean securityEnabled;

    /**
     * The principal requests are given when authentication is switched off.
     *
     * <p>Configurable so a developer's locally-created designs are attributable and two
     * developers sharing a database do not overwrite each other's rows.
     */
    @Value("${archforge.security.local-user:local-dev}")
    private String localUser;

    @Bean
    public SecurityFilterChain designServiceSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS));

        if (securityEnabled) {
            http.authorizeHttpRequests(auth -> auth
                            .requestMatchers(
                                    "/actuator/health",
                                    "/actuator/health/**",
                                    "/actuator/info",
                                    "/actuator/health/liveness",
                                    "/actuator/health/readiness")
                            .permitAll()
                            .requestMatchers("/actuator/**").hasRole("ADMIN")
                            // No unauthenticated design read or write. This is the whole point
                            // of the service: it holds every user's designs and every
                            // measurement taken of them.
                            .requestMatchers("/api/v1/**").authenticated()
                            .anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults());
        } else {
            // Local development only. permitAll, not merely "no basic auth": disabling the
            // filter while leaving `authenticated()` in place produces a 403 for everyone,
            // which is how the first version of this ended up looking like it required a
            // credential it had been told not to ask for.
            //
            // permitAll also leaves the security context empty, which is why a named
            // principal is installed. See LocalDevelopmentPrincipalFilter for why making the
            // controllers null-tolerant instead would be the wrong fix.
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .httpBasic(httpBasic -> httpBasic.disable())
                    .addFilterBefore(new LocalDevelopmentPrincipalFilter(localUser),
                            UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        if (!securityEnabled) {
            return new InMemoryUserDetailsManager();
        }
        if (isBlank(user) || isBlank(password)) {
            throw new IllegalStateException(
                    "archforge.security.user and archforge.security.password must be set. Refusing "
                            + "to start with a default or missing credential. Set them, or set "
                            + "archforge.security.enabled=false for local development.");
        }
        InMemoryUserDetailsManager manager = new InMemoryUserDetailsManager();
        User.UserBuilder builder = User.withUsername(user)
                .password(passwordEncoder.encode(password))
                .roles("USER");
        manager.createUser(builder.build());

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