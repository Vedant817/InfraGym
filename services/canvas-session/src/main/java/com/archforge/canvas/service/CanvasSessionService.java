package com.archforge.canvas.service;

import com.archforge.canvas.model.CanvasState;
import com.archforge.canvas.model.SessionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class CanvasSessionService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${canvas.session.ttl-minutes:60}")
    private int sessionTtlMinutes;

    @Value("${canvas.session.max-users-per-session:10}")
    private int maxUsersPerSession;

    private static final String SESSION_PREFIX = "canvas:session:";
    private static final String USERS_PREFIX = "canvas:users:";
    private static final String CANVAS_PREFIX = "canvas:state:";

    public void joinSession(String sessionId, String userId) {
        String usersKey = USERS_PREFIX + sessionId;
        redisTemplate.opsForSet().add(usersKey, userId);
        redisTemplate.expire(usersKey, Duration.ofMinutes(sessionTtlMinutes));

        log.info("User {} joined session {}. Total users: {}", userId, sessionId,
                redisTemplate.opsForSet().size(usersKey));
    }

    public void leaveSession(String sessionId, String userId) {
        String usersKey = USERS_PREFIX + sessionId;
        redisTemplate.opsForSet().remove(usersKey, userId);

        Long remainingUsers = redisTemplate.opsForSet().size(usersKey);
        if (remainingUsers == null || remainingUsers == 0) {
            redisTemplate.delete(usersKey);
            redisTemplate.delete(CANVAS_PREFIX + sessionId);
            log.info("Session {} closed - no users remaining", sessionId);
        } else {
            log.info("User {} left session {}. Remaining users: {}", userId, sessionId, remainingUsers);
        }
    }

    public void updateCanvasState(String sessionId, String topologyJson) {
        String canvasKey = CANVAS_PREFIX + sessionId;
        redisTemplate.opsForValue().set(canvasKey, topologyJson,
                Duration.ofMinutes(sessionTtlMinutes));
        log.debug("Updated canvas state for session: {}", sessionId);
    }

    public Optional<CanvasState> getCanvasState(String sessionId) {
        String canvasKey = CANVAS_PREFIX + sessionId;
        String topologyJson = redisTemplate.opsForValue().get(canvasKey);

        if (topologyJson == null) {
            return Optional.empty();
        }

        String usersKey = USERS_PREFIX + sessionId;
        Set<String> users = redisTemplate.opsForSet().members(usersKey);

        return Optional.of(CanvasState.builder()
                .sessionId(sessionId)
                .topologyJson(topologyJson)
                .connectedUsers(users != null ? new ArrayList<>(users) : Collections.emptyList())
                .lastUpdated(Instant.now())
                .build());
    }

    public boolean acquireLock(String sessionId, String userId) {
        String lockKey = "canvas:lock:" + sessionId;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, userId, Duration.ofSeconds(30));
        return Boolean.TRUE.equals(acquired);
    }

    public void releaseLock(String sessionId) {
        String lockKey = "canvas:lock:" + sessionId;
        redisTemplate.delete(lockKey);
    }

    public int getConnectedUserCount(String sessionId) {
        String usersKey = USERS_PREFIX + sessionId;
        Long count = redisTemplate.opsForSet().size(usersKey);
        return count != null ? count.intValue() : 0;
    }
}
