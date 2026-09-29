package com.archforge.gateway.websocket;

import com.archforge.contracts.canvas.CanvasTopology;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;


@Component
public class CanvasWebSocketHandler implements WebSocketHandler {
    private final KafkaTemplate<String, CanvasTopology> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public CanvasWebSocketHandler(KafkaTemplate<String, CanvasTopology> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        return session.receive()
            .map(message -> message.getPayloadAsText())
            .flatMap(payload -> {
                try {
                    CanvasTopology topology = objectMapper.readValue(payload, CanvasTopology.class);
                    return Mono.fromFuture(kafkaTemplate.send("design.submissions.raw", topology.submissionId(), topology));
                } catch (Exception e) {
                    return Mono.error(new RuntimeException("Invalid Canvas JSON", e));
                }
            })
            .then();
    }
}
