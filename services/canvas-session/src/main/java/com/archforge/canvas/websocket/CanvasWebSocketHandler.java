package com.archforge.canvas.websocket;

import com.archforge.canvas.model.SessionEvent;
import com.archforge.canvas.service.CanvasSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class CanvasWebSocketHandler extends TextWebSocketHandler {

    private final CanvasSessionService sessionService;
    private final ObjectMapper objectMapper;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = getSessionId(session);
        String userId = getUserId(session);

        log.info("WebSocket connection established: session={}, user={}", sessionId, userId);

        sessions.put(session.getId(), session);
        sessionService.joinSession(sessionId, userId);

        broadcastToSession(sessionId, SessionEvent.builder()
                .sessionId(sessionId)
                .eventType("USER_JOINED")
                .userId(userId)
                .timestamp(java.time.Instant.now())
                .build());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = getSessionId(session);
        String userId = getUserId(session);

        log.debug("Received message from user {} in session {}: {}", userId, sessionId, message.getPayload());

        SessionEvent event = objectMapper.readValue(message.getPayload(), SessionEvent.class);

        switch (event.getEventType()) {
            case "CANVAS_UPDATE" -> {
                sessionService.updateCanvasState(sessionId, event.getPayload());
                broadcastToSession(sessionId, event);
            }
            case "USER_CURSOR" -> broadcastToSession(sessionId, event);
            case "SUBMIT_DESIGN" -> {
                sessionService.updateCanvasState(sessionId, event.getPayload());
                broadcastToSession(sessionId, event);
            }
            default -> log.warn("Unknown event type: {}", event.getEventType());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = getSessionId(session);
        String userId = getUserId(session);

        log.info("WebSocket connection closed: session={}, user={}, status={}", sessionId, userId, status);

        sessions.remove(session.getId());
        sessionService.leaveSession(sessionId, userId);

        broadcastToSession(sessionId, SessionEvent.builder()
                .sessionId(sessionId)
                .eventType("USER_LEFT")
                .userId(userId)
                .timestamp(java.time.Instant.now())
                .build());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error for session {}", session.getId(), exception);
    }

    private void broadcastToSession(String sessionId, SessionEvent event) {
        String message;
        try {
            message = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("Failed to serialize event", e);
            return;
        }

        sessions.values().stream()
                .filter(s -> sessionId.equals(getSessionId(s)))
                .forEach(s -> {
                    try {
                        synchronized (s) {
                            s.sendMessage(new TextMessage(message));
                        }
                    } catch (IOException e) {
                        log.error("Failed to send message to session {}", s.getId(), e);
                    }
                });
    }

    private String getSessionId(WebSocketSession session) {
        return session.getUri() != null && session.getUri().getQuery() != null
                ? getQueryParam(session.getUri().getQuery(), "sessionId")
                : "default";
    }

    private String getUserId(WebSocketSession session) {
        return session.getUri() != null && session.getUri().getQuery() != null
                ? getQueryParam(session.getUri().getQuery(), "userId")
                : "anonymous";
    }

    private String getQueryParam(String query, String paramName) {
        for (String param : query.split("&")) {
            String[] keyValue = param.split("=");
            if (keyValue.length == 2 && keyValue[0].equals(paramName)) {
                return keyValue[1];
            }
        }
        return null;
    }
}
