package com.archforge.graphcompiler.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * CORS for the browser frontend.
 *
 * <p>The previous version configured no CORS at all, so any cross-origin request from
 * the Next.js dev server was rejected by the browser before it was useful — the service
 * was unusable from the very client it exists for.
 *
 * <p>Origins come from configuration and are never {@code "*"}. A wildcard cannot be
 * combined with credentials, so it does not actually work for an authenticated API, and
 * permitting every origin on a credentialed endpoint is an exfiltration primitive: any
 * site the user visits could read their designs. Listing the real dev-server origins is
 * both correct and smaller.
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter(@Value("${archforge.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();

        // allowCredentials requires explicit origins, not a wildcard.
        configuration.setAllowedOrigins(allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "Origin",
                "X-Requested-With", "X-User-Id", "X-Correlation-Id"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id", "Location"));
        configuration.setAllowCredentials(true);
        // Preflight is cheap and the topology is large; a short cache keeps the
        // OPTIONS storm off the compiler.
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return new CorsFilter(source);
    }
}
