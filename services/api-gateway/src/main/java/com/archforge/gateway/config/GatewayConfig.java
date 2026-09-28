package com.archforge.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

@Configuration
public class GatewayConfig {

    @Value("${gateway.rate-limit.requests-per-minute:60}")
    private int requestsPerMinute;

    @Value("${gateway.rate-limit.burst-capacity:10}")
    private int burstCapacity;

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("graph-compiler-route", r -> r.path("/api/v1/compile/**")
                        .filters(f -> f
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(redisRateLimiter())
                                        .setKeyResolver(userKeyResolver()))
                                .circuitBreaker(c -> c
                                        .setName("graphCompilerCircuitBreaker")
                                        .setFallbackUri("forward:/fallback/graph-compiler")))
                        .uri("${GRAPH_COMPILER_URL:http://localhost:8081}"))
                .route("canvas-session-route", r -> r.path("/api/v1/session/**")
                        .filters(f -> f
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(redisRateLimiter())
                                        .setKeyResolver(userKeyResolver())))
                        .uri("${CANVAS_SESSION_URL:http://localhost:8085}"))
                .route("ai-evaluator-route", r -> r.path("/api/v1/evaluate/**")
                        .filters(f -> f
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(redisRateLimiter())
                                        .setKeyResolver(userKeyResolver())))
                        .uri("${AI_EVALUATOR_URL:http://localhost:8084}"))
                .route("websocket-route", r -> r.path("/ws/**")
                        .uri("${CANVAS_SESSION_URL:http://localhost:8085}"))
                .build();
    }

    @Bean
    public org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter redisRateLimiter() {
        return new org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter(
                requestsPerMinute, burstCapacity);
    }

    @Bean
    public org.springframework.cloud.gateway.filter.ratelimit.KeyResolver userKeyResolver() {
        return exchange -> reactor.core.publisher.Mono.justOrEmpty(
                exchange.getRequest().getHeaders().getFirst("X-User-Id"))
                .defaultIfEmpty(exchange.getRequest().getRemoteAddress() != null
                        ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                        : "unknown");
    }
}
