package com.example.chat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

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
    private final String uploadDir;

    public WebConfig(ChatProperties properties,
                     @Value("${app.upload.dir:./uploads}") String uploadDir) {
        this.properties = properties;
        this.uploadDir = uploadDir;
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
        // Must be derived from the same property UploadService writes to: a
        // hard-coded "./uploads/" would keep storing files where the operator
        // asked and serve them from somewhere else whenever UPLOAD_DIR is set.
        String location = Paths.get(uploadDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location, "classpath:/static/uploads/");
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
