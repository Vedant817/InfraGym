package com.archforge.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "archforge.security.compile-user=compiler", "archforge.security.compile-password=compiler-test-password",
        "archforge.security.user=designer", "archforge.security.password=designer-test-password",
        "archforge.security.operator-admin-user=operator", "archforge.security.operator-admin-password=operator-test-password",
        "management.health.redis.enabled=false"
})
@Import(GatewaySecurityTest.CaptureConfiguration.class)
class GatewaySecurityTest {
    @Autowired ApplicationContext context;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired CaptureFilter capture;
    // Spy creation still constructs the REAL limiter with the shipped defaults.
    // Only Redis I/O is replaced; these HTTP tests need no external service.
    @MockitoSpyBean RedisRateLimiter limiter;
    private WebTestClient client;

    @BeforeEach
    void prepareClient() {
        client = WebTestClient.bindToApplicationContext(context).configureClient().baseUrl("http://localhost:8080")
                .responseTimeout(Duration.ofSeconds(10)).build();
        breakers.getAllCircuitBreakers().forEach(b -> b.reset());
        doReturn(Mono.just(new RateLimiter.Response(true, Map.of())))
                .when(limiter).isAllowed(anyString(), anyString());
        clearInvocations(limiter);
        capture.exchange.set(null);
    }

    @Test
    void identityHeadersAndInvalidCredentialsCannotAuthenticate() {
        client.post().uri("/api/v1/compile").header("X-User-Id", "operator")
                .exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/compile")
                .headers(h -> h.setBasicAuth("compiler", "wrong-password"))
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/ws/canvas").header("X-User-Id", "operator")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void healthIsPublicButActuatorRoutesRequireAnAdministrator() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist();
        client.get().uri("/actuator/gateway/routes").exchange().expectStatus().isUnauthorized();
        client.get().uri("/actuator/gateway/routes")
                .headers(h -> h.setBasicAuth("compiler", "compiler-test-password"))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/actuator/gateway/routes")
                .headers(h -> h.setBasicAuth("operator", "operator-test-password"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(6);
        client.post().uri("/actuator/gateway/refresh")
                .headers(h -> h.setBasicAuth("operator", "operator-test-password"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void allowedCorsPreflightWorksBeforeAuthenticationAndOtherOriginsAreRejected() {
        client.options().uri("/api/v1/designs").header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:3000")
                .expectHeader().doesNotExist("Access-Control-Allow-Credentials");
        client.options().uri("/api/v1/designs").header("Origin", "https://attacker.example")
                .header("Access-Control-Request-Method", "POST")
                .exchange().expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
        client.get().uri("/actuator/health").header("Origin", "https://attacker.example")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void authenticatedIdentityLimitsRequestsAndAuthorizationIsPreservedDownstream() {
        client.get().uri("/api/v1/designs/header-check")
                .headers(h -> h.setBasicAuth("designer", "designer-test-password"))
                .header("x-user-id", "victim", "fresh-budget")
                .exchange().expectStatus().isOk();
        verify(limiter).isAllowed("design-service", "designer");
        var request = capture.exchange.get().getRequest();
        assertThat(request.getHeaders()).doesNotContainKey("X-User-Id");
        var expected = new HttpHeaders();
        expected.setBasicAuth("designer", "designer-test-password");
        assertThat(request.getHeaders().getFirst("Authorization"))
                .isEqualTo(expected.getFirst("Authorization"));
    }

    @Test
    void conditionalSavePreflightAllowsIfMatchAndForwardsTheHeader() {
        client.options().uri("/api/v1/designs/header-check")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "authorization,content-type,if-match")
                .exchange().expectStatus().isOk()
                .expectHeader().valueMatches("Access-Control-Allow-Headers", "(?i).*if-match.*");
        client.put().uri("/api/v1/designs/header-check")
                .headers(h -> h.setBasicAuth("designer", "designer-test-password"))
                .header("Origin", "http://localhost:3000").header("If-Match", "\"7\"")
                .bodyValue(Map.of("topology", Map.of()))
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Expose-Headers", "ETag");
        assertThat(capture.exchange.get().getRequest().getHeaders().getFirst("If-Match")).isEqualTo("\"7\"");
    }

    @Test
    void exhaustedBudgetReturns429() {
        doReturn(Mono.just(new RateLimiter.Response(false, Map.of())))
                .when(limiter).isAllowed("design-service", "designer");
        client.get().uri("/api/v1/designs/header-check")
                .headers(h -> h.setBasicAuth("designer", "designer-test-password"))
                .exchange().expectStatus().isEqualTo(429);
        assertThat(capture.exchange.get()).isNull();
    }

    @Test
    void openCompilerCircuitForwardsPostTo503InsteadOf405() {
        breakers.circuitBreaker("graph-compilerCircuitBreaker").transitionToForcedOpenState();
        client.post().uri("/api/v1/compile")
                .headers(h -> h.setBasicAuth("compiler", "compiler-test-password"))
                .bodyValue(Map.of("nodes", new Object[0]))
                .exchange().expectStatus().isEqualTo(503).expectBody()
                .jsonPath("$.error").isEqualTo("Graph Compiler service is temporarily unavailable");
    }

    @Test
    void sessionEvaluationAndDesignRoutesHaveWorkingNonGetFallbacks() {
        for (String[] route : new String[][]{
                {"canvas-session", "/api/v1/session/123"},
                {"ai-evaluator", "/api/v1/evaluate/123"},
                {"design-service", "/api/v1/designs/123"}}) {
            breakers.circuitBreaker(route[0] + "CircuitBreaker").transitionToForcedOpenState();
            client.put().uri(route[1])
                    .headers(h -> h.setBasicAuth("designer", "designer-test-password"))
                    .bodyValue(Map.of()).exchange().expectStatus().isEqualTo(503);
        }
    }

    @Test
    void adminApiRejectsOrdinaryUsersAndRoutesAuthorizedOperators() {
        client.post().uri("/api/v1/admin/replay-dlq")
                .headers(h -> h.setBasicAuth("compiler", "compiler-test-password"))
                .exchange().expectStatus().isForbidden();
        breakers.circuitBreaker("graph-compilerCircuitBreaker").transitionToForcedOpenState();
        client.post().uri("/api/v1/admin/replay-dlq")
                .headers(h -> h.setBasicAuth("operator", "operator-test-password"))
                .exchange().expectStatus().isEqualTo(503);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CaptureConfiguration {
        @Bean CaptureFilter captureFilter() { return new CaptureFilter(); }
    }

    static class CaptureFilter implements GlobalFilter, Ordered {
        final AtomicReference<ServerWebExchange> exchange = new AtomicReference<>();
        @Override public int getOrder() { return 10000; }
        @Override public Mono<Void> filter(ServerWebExchange current,
                org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
            if (current.getRequest().getPath().value().endsWith("/header-check")) {
                exchange.set(current);
                current.getResponse().setStatusCode(HttpStatus.OK);
                return current.getResponse().setComplete();
            }
            return chain.filter(current);
        }
    }
}
