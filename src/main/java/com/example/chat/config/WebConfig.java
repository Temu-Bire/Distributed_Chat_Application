package com.example.chat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
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
                .allowedOriginPatterns(properties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:./uploads/", "classpath:/static/uploads/");
    }

    /**
     * "/" is the public landing page; the application itself lives at
     * "/app". /index.html keeps serving the app for existing bookmarks and the
     * automated checks that reference it directly.
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/landing.html");
        registry.addViewController("/app").setViewName("forward:/index.html");
    }
}
