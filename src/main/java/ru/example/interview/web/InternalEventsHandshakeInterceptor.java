package ru.example.interview.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Проверяет общий секрет до открытия внутреннего WebSocket STT → аналитика. */
@Component
public final class InternalEventsHandshakeInterceptor implements HandshakeInterceptor {
    private final byte[] expectedToken;

    public InternalEventsHandshakeInterceptor(
            @Value("${interview.events.token:${INTERVIEW_EVENTS_TOKEN:}}") String token) {
        expectedToken = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        String provided = request.getHeaders().getFirst("X-Internal-Token");
        byte[] actual = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
        boolean allowed = expectedToken.length >= 32 && MessageDigest.isEqual(expectedToken, actual);
        if (!allowed) response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return allowed;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
        // Секрет нужен только на этапе handshake и не сохраняется в атрибутах сеанса.
    }
}
