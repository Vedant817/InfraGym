package com.archforge.gateway;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

// Uses the shipped rate-limit defaults and REAL beans: mocking RedisRateLimiter
// here would hide its constructor failure, just as missing context tests did.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class GatewayContextTest {
    @Autowired ApplicationContext context;
    @Autowired RouteLocator routeLocator;
    @Autowired GatewayProperties properties;
    @Autowired RedisRateLimiter rateLimiter;
    @Autowired io.github.resilience4j.timelimiter.TimeLimiterRegistry timeLimiters;

    @Test
    void contextStartsWithExactlyOnePrincipalResolverAndValidMinuteLimiter() {
        assertThat(context.getBeansOfType(KeyResolver.class)).containsOnlyKeys("userKeyResolver");
        var defaults = (RedisRateLimiter.Config) ReflectionTestUtils.getField(rateLimiter, "defaultConfig");
        assertThat(defaults.getReplenishRate()).isEqualTo(60);
        assertThat(defaults.getBurstCapacity()).isEqualTo(600);
        assertThat(defaults.getRequestedTokens()).isEqualTo(60);
        assertThat(timeLimiters.getDefaultConfig().getTimeoutDuration()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void effectiveRoutesHaveExactlyTheExpectedIdsAndResolvedTargets() {
        assertThat(properties.getRoutes()).isEmpty();
        List<Route> routes = routes();
        assertThat(routes).extracting(Route::getId).doesNotHaveDuplicates().containsExactlyInAnyOrder(
                "graph-compiler", "canvas-session", "ai-evaluator", "design-service",
                "graph-compiler-admin", "websocket");
        assertThat(routes).allSatisfy(route -> {
            assertThat(route.getUri().getHost()).isEqualTo("localhost");
            assertThat(route.getFilters().toString()).contains("RequestRateLimiter", "SpringCloudCircuitBreaker");
        });
        assertThat(routes).filteredOn(r -> r.getId().equals("design-service"))
                .extracting(r -> r.getUri().toString()).containsExactly("http://localhost:8085");
        assertThat(routes).filteredOn(r -> r.getId().equals("websocket"))
                .extracting(r -> r.getUri().toString()).containsExactly("ws://localhost:8086");
        assertThat(routes).filteredOn(r -> r.getId().equals("canvas-session"))
                .extracting(r -> r.getUri().toString()).containsExactly("http://localhost:8086");
    }

    @Test
    void everyPublicPathMatchesOneProtectedRoute() {
        Map<String, String> paths = Map.ofEntries(
                Map.entry("/api/v1/compile", "graph-compiler"),
                Map.entry("/api/v1/compile/statuses", "graph-compiler"),
                Map.entry("/api/v1/session/123", "canvas-session"),
                Map.entry("/api/v1/evaluate/123", "ai-evaluator"),
                Map.entry("/api/v1/designs", "design-service"),
                Map.entry("/api/v1/designs/scenarios", "design-service"),
                Map.entry("/api/v1/designs/123/submissions", "design-service"),
                Map.entry("/api/v1/submissions/123/evaluation", "design-service"),
                Map.entry("/api/v1/admin/replay-dlq", "graph-compiler-admin"),
                Map.entry("/ws/canvas", "websocket"));
        paths.forEach((path, expected) -> assertThat(routes().stream()
                .filter(route -> Boolean.TRUE.equals(Mono.from(route.getPredicate().apply(
                        MockServerWebExchange.from(MockServerHttpRequest.get(path)))).block(Duration.ofSeconds(5))))
                .map(Route::getId).toList()).as(path).containsExactly(expected));
    }

    private List<Route> routes() {
        return routeLocator.getRoutes().collectList().block(Duration.ofSeconds(5));
    }
}
