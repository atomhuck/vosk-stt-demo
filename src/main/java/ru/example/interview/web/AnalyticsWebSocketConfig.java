package ru.example.interview.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class AnalyticsWebSocketConfig implements WebSocketConfigurer {
    private final EventInterviewWebSocketHandler handler;
    private final InternalEventsHandshakeInterceptor authentication;

    public AnalyticsWebSocketConfig(EventInterviewWebSocketHandler handler,
                                    InternalEventsHandshakeInterceptor authentication) {
        this.handler = handler;
        this.authentication = authentication;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/interview-events").addInterceptors(authentication);
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(64 * 1024);
        container.setMaxTextMessageBufferSize(8 * 1024);
        return container;
    }
}
