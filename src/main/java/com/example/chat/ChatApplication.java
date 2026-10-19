package com.example.chat;

import com.example.chat.config.ChatProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the real-time chat web application.
 *
 * <p>Single Spring Boot instance that serves the static frontend from
 * {@code src/main/resources/static} and hosts the STOMP-over-WebSocket
 * endpoint at {@code /ws}. Everything runs in one JVM with an in-process
 * broker — see {@code docs/architecture.md} for how this evolves into a
 * multi-instance deployment.</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(ChatProperties.class)
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
