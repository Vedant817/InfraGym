package com.archforge.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GatewayConfig {

    @Bean
    public RouteLocator gatewayRoutes(RouteLocatorBuilder builder,
                                      RedisRateLimiter rateLimiter,
                                      KeyResolver userKeyResolver,
                                      @Value("${gateway.targets.graph-compiler}") String compiler,
                                      @Value("${gateway.targets.canvas-session}") String canvas,
                                      @Value("${gateway.targets.ai-evaluator}") String evaluator,
                                      @Value("${gateway.targets.design-service}") String design,
                                      @Value("${gateway.targets.canvas-websocket}") String websocket) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        addRoute(routes, "graph-compiler", compiler, "graph-compiler", rateLimiter,
                userKeyResolver, "/api/v1/compile/**");
        addRoute(routes, "canvas-session", canvas, "canvas-session", rateLimiter,
                userKeyResolver, "/api/v1/session/**");
        addRoute(routes, "ai-evaluator", evaluator, "ai-evaluator", rateLimiter,
                userKeyResolver, "/api/v1/evaluate/**");
        addRoute(routes, "design-service", design, "design-service", rateLimiter,
                userKeyResolver, "/api/v1/designs/**", "/api/v1/submissions/**");
        addRoute(routes, "graph-compiler-admin", compiler, "graph-compiler", rateLimiter,
                userKeyResolver, "/api/v1/admin/**");
        addRoute(routes, "websocket", websocket, "canvas-session", rateLimiter,
                userKeyResolver, "/ws/**");
        return routes.build();
    }

    private void addRoute(RouteLocatorBuilder.Builder routes, String id, String uri,
                          String service, RedisRateLimiter rateLimiter,
                          KeyResolver keyResolver, String... paths) {
        routes.route(id, r -> r.path(paths)
                .filters(f -> f
                        // Downstream Basic authentication receives Authorization unchanged.
                        // Unverified identity headers must never compete with that principal.
                        .removeRequestHeader("X-User-Id")
                        .requestRateLimiter(c -> c.setRateLimiter(rateLimiter).setKeyResolver(keyResolver))
                        .circuitBreaker(c -> c.setName(service + "CircuitBreaker")
                                .setFallbackUri("forward:/fallback/" + service)))
                .uri(uri));
    }
}
