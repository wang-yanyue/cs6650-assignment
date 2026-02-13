package org.example.chatflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides an ObjectMapper bean so WebSocket and REST code can use JSON.
 * Spring Boot 4.x with spring-boot-starter-webmvc may not auto-configure one.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
