package com.exam.backend.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class CorsConfig {

    /**
     * Comma-separated list of browser origins allowed to call the API.
     * Configured in application.properties via {@code app.cors-origins}.
     * Add the external integration portal's origin to that property when needed.
     */
    @Value("${app.cors-origins:https://hr-orbit.azalio.io,http://localhost:*,http://127.0.0.1:*}")
    private String corsOrigins;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
            	CorsConfig.log.info("Initializing custom CORS mappings for allowed origins");
                List<String> origins = new ArrayList<>();
                if (corsOrigins != null && !corsOrigins.isBlank()) {
                    for (String o : corsOrigins.split(",")) {
                        String trimmed = o.trim();
                        if (!trimmed.isEmpty()) origins.add(trimmed);
                    }
                }

                registry.addMapping("/**")
                        .allowedOriginPatterns(origins.toArray(new String[0]))
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .exposedHeaders("Authorization")
                        .allowCredentials(true)
                        .maxAge(3600);
            }
        };
    }
}
