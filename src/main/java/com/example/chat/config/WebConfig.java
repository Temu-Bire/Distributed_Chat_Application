package com.example.chat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * HTTP-side configuration.
 *
 * <p>The browser frontend is served from the same origin as the API, so it
 * needs no CORS. This mapping exists for the case where the REST surface grows
 * (message history, authentication) and is consumed from a different
 * development origin. It reuses {@code chat.allowed-origins} so a single
 * setting governs both the WebSocket handshake and HTTP access.</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ChatProperties properties;

    public WebConfig(ChatProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.allowedOrigins().toArray(String[]::new))
                .allowedMethods(HttpMethod.GET.name())
                .maxAge(3600);
    }
}
