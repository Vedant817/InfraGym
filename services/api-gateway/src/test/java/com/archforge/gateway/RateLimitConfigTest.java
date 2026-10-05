package com.archforge.gateway;

import com.archforge.gateway.config.RateLimitConfig;
import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitConfigTest {
    private final RateLimitConfig config = new RateLimitConfig();

    @Test
    void oneRequestPerMinuteCostsSixtySecondsOfReplenishment() {
        var limiter = (RedisRateLimiter.Config) ReflectionTestUtils.getField(
                config.redisRateLimiter(1, 10), "defaultConfig");
        assertThat(limiter.getReplenishRate()).isEqualTo(1);
        assertThat(limiter.getRequestedTokens()).isEqualTo(60);
        assertThat(limiter.getBurstCapacity() / limiter.getRequestedTokens()).isEqualTo(10);
    }

    @Test
    void invalidOrOverflowingRateSettingsFailClearly() {
        assertThatThrownBy(() -> config.redisRateLimiter(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.redisRateLimiter(60, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.redisRateLimiter(601, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.redisRateLimiter(60, Integer.MAX_VALUE)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void spoofedAndRepeatedIdentityHeadersNeverChangeAnAuthenticatedKey() {
        var resolver = config.userKeyResolver();
        for (String spoofed : new String[]{"victim", "new-budget"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/compile")
                    .header("x-user-id", spoofed, "another-victim")
                    .header("X-Forwarded-For", "192.0.2.1"))
                    .mutate().principal(Mono.just((Principal) () -> "alice")).build();
            assertThat(resolver.resolve(exchange).block()).isEqualTo("alice");
        }
        var bob = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/compile"))
                .mutate().principal(Mono.just((Principal) () -> "bob")).build();
        assertThat(resolver.resolve(bob).block()).isEqualTo("bob");
    }

    @Test
    void aHeaderAloneDoesNotEstablishAPrincipal() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/compile")
                .header("X-User-Id", "admin"));
        assertThat(config.userKeyResolver().resolve(exchange).block()).isNull();
    }
}
