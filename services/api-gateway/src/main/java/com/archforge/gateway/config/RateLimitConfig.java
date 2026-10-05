package com.archforge.gateway.config;

import java.security.Principal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    @Bean
    public KeyResolver userKeyResolver() {
        // Downstream services use Basic auth. Spring Security verifies the same
        // configured credentials here, so the principal (not X-User-Id or a raw
        // Authorization value) is a stable, authenticated rate-limit identity.
        return exchange -> exchange.getPrincipal().map(Principal::getName);
    }

    @Bean
    public RedisRateLimiter redisRateLimiter(
            @Value("${gateway.rate-limit.requests-per-minute:60}") int requestsPerMinute,
            @Value("${gateway.rate-limit.burst-capacity:10}") int burstCapacity) {
        Assert.isTrue(requestsPerMinute > 0, "requests-per-minute must be positive");
        Assert.isTrue(burstCapacity > 0, "burst-capacity must be positive");
        // Redis replenishes per second. Charging 60 tokens/request expresses RPM
        // exactly, including rates below one request/second. Burst is in requests.
        int tokenCapacity = Math.multiplyExact(burstCapacity, 60);
        Assert.isTrue(tokenCapacity >= requestsPerMinute,
                "burst-capacity must hold at least one second of replenishment");
        return new RedisRateLimiter(requestsPerMinute, tokenCapacity, 60);
    }
}
