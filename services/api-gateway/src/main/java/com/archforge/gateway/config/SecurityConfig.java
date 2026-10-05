package com.archforge.gateway.config;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /**
     * The property name the gateway's own administrator used to be bound from.
     *
     * <p>{@code SystemEnvironmentPropertySource} resolves this key against
     * {@code ARCHFORGE_SECURITY_ADMIN_USER}, which is also the variable the
     * design-service administrator is configured from. While the YAML still declared
     * {@code archforge.security.admin-user: ${ADMIN_USER:}}, exporting both variables
     * made the environment one win: the {@code ADMIN_USER} credential silently stopped
     * existing and every administrator request returned 401 with no log line anywhere.
     *
     * <p>The slot is now {@code operator-admin-user}. This constant is the tripwire: if
     * the old name ever comes back - in YAML, in a {@code --archforge.security.*} flag, or
     * by renaming the operator slot again - the gateway refuses to start instead of
     * quietly authenticating a different administrator than the operator configured.
     */
    public static final String COLLIDING_ADMIN_PROPERTY = "archforge.security.admin-user";

    @Bean
    public LegacyAdminSlotGuard legacyAdminSlotGuard(Environment environment) {
        if (environment.containsProperty(COLLIDING_ADMIN_PROPERTY)) {
            throw new IllegalStateException(
                    COLLIDING_ADMIN_PROPERTY + " is configured, but ARCHFORGE_SECURITY_ADMIN_USER "
                    + "relax-binds onto that exact name and silently overwrites the gateway's own "
                    + "ADMIN_USER credential. The gateway administrator is now "
                    + "archforge.security.operator-admin-user (ADMIN_USER); ARCHFORGE_SECURITY_ADMIN_USER "
                    + "configures only the design-service administrator. Unset "
                    + COLLIDING_ADMIN_PROPERTY + " and restart.");
        }
        return new LegacyAdminSlotGuard();
    }

    /** Marker bean proving the collision check ran during startup. */
    public record LegacyAdminSlotGuard() { }

    @Bean
    public SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http,
                                                  CorsConfigurationSource cors) {
        return http
                // Stateless API clients send Basic Authorization on each request.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(c -> c.requestCache(NoOpServerRequestCache.getInstance()))
                .cors(c -> c.configurationSource(cors))
                .authorizeExchange(auth -> auth
                        .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/actuator/**").hasRole("ADMIN")
                        // No actuator mutations, even with an administrator credential.
                        .pathMatchers("/actuator/**").denyAll()
                        .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyExchange().authenticated())
                .httpBasic(Customizer.withDefaults())
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .build();
    }

    @Bean
    public ReactiveUserDetailsService gatewayUsers(
            PasswordEncoder encoder,
            @Value("${archforge.security.compile-user:}") String compileUser,
            @Value("${archforge.security.compile-password:}") String compilePassword,
            @Value("${archforge.security.user:}") String designUser,
            @Value("${archforge.security.password:}") String designPassword,
            @Value("${archforge.security.operator-admin-user:}") String adminUser,
            @Value("${archforge.security.operator-admin-password:}") String adminPassword,
            @Value("${archforge.security.design-admin-user:}") String designAdminUser,
            @Value("${archforge.security.design-admin-password:}") String designAdminPassword) {
        Map<String, UserDetails> users = new LinkedHashMap<>();
        addUser(users, encoder, compileUser, compilePassword, false);
        addUser(users, encoder, designUser, designPassword, false);
        addUser(users, encoder, adminUser, adminPassword, true);
        addUser(users, encoder, designAdminUser, designAdminPassword, true);
        // Empty credentials never enable anonymous access or a generated default user.
        return users.isEmpty() ? username -> Mono.empty() : new MapReactiveUserDetailsService(users.values());
    }

    private void addUser(Map<String, UserDetails> users, PasswordEncoder encoder,
                         String username, String password, boolean admin) {
        if (username.isBlank() && password.isBlank()) {
            return;
        }
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalArgumentException("Both username and password must be configured");
        }
        // Both Spring Basic user stores use case-insensitive lookup. Detect
        // credential collisions before MapReactiveUserDetailsService normalizes them.
        String accountKey = username.toLowerCase(Locale.ROOT);
        UserDetails existing = users.get(accountKey);
        if (existing != null && !encoder.matches(password, existing.getPassword())) {
            throw new IllegalArgumentException("Downstream credentials for the same username must agree");
        }
        var authorities = new LinkedHashSet<String>();
        authorities.add("ROLE_USER");
        if (existing != null) {
            existing.getAuthorities().forEach(a -> authorities.add(a.getAuthority()));
        }
        if (admin) {
            authorities.add("ROLE_ADMIN");
        }
        users.put(accountKey, User.withUsername(existing == null ? username : existing.getUsername())
                .password(existing == null ? encoder.encode(password) : existing.getPassword())
                .authorities(authorities.toArray(String[]::new)).build());
    }

    @Bean
    public PasswordEncoder gatewayPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource gatewayCors(
            @Value("${gateway.cors.allowed-origins:http://localhost:3000}") String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        for (String origin : origins) {
            URI uri = URI.create(origin);
            if (origin.contains("*") || uri.getHost() == null || uri.getUserInfo() != null
                    || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || !uri.getRawPath().isEmpty() || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("CORS requires exact HTTP(S) origins");
            }
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Request-Id", "If-Match"));
        config.setExposedHeaders(List.of("ETag"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
