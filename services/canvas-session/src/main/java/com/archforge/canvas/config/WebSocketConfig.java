package com.archforge.canvas.config;

import com.archforge.canvas.websocket.CanvasWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final CanvasWebSocketHandler canvasWebSocketHandler;

    @Value("${canvas.websocket.endpoint:/ws/canvas}")
    private String websocketEndpoint;

    @Value("${canvas.websocket.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(canvasWebSocketHandler, websocketEndpoint)
                .setAllowedOrigins(allowedOrigins.split(","));
    }
}
