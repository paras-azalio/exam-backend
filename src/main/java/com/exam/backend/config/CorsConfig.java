package com.exam.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
            	log.info("Initializing custom CORS mappings for allowed origins");
                registry.addMapping("/**")
                        .allowedOriginPatterns(
                            "https://hr-orbit.azalio.io",
                            "http://localhost:*",
                            "http://127.0.0.1:*",
                            // HTTPS dev origins (Vite now serves the frontend over HTTPS).
                            "https://localhost:*",
                            "https://127.0.0.1:*"
                            // LAN proctoring: allow the dev host's own LAN subnets so a
                            // candidate on another machine (hitting the Vite host's IP) is
                            // accepted. Add your subnet here if it differs.
//                            "http://172.15.*.*:*",
//                            "https://172.15.*.*:*",
//                            "http://172.23.*.*:*",
//                            "https://172.23.*.*:*"
                        )
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .exposedHeaders("Authorization")
                        .allowCredentials(true)
                        .maxAge(3600);
            }
        };
    }
}
