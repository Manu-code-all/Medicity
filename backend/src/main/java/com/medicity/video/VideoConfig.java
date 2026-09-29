package com.medicity.video;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.List;

/** The video room's socket, open to the same origins as the API. */
@Configuration
@EnableWebSocket
public class VideoConfig implements WebSocketConfigurer {

    private final VideoSignalling signalling;
    private final List<String> allowedOrigins;

    public VideoConfig(VideoSignalling signalling,
                       @Value("${medicity.cors.allowed-origins}") List<String> allowedOrigins) {
        this.signalling = signalling;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(signalling, "/ws/video")
                .addInterceptors(signalling)
                .setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }
}
