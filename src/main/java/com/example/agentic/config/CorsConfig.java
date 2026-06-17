package com.example.agentic.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * WebFlux CORS 配置。
 * <p>
 * 允许前端 Next.js dev server (localhost:3000) 跨域访问 AG-UI 端点。
 */
@Configuration
public class CorsConfig implements WebFluxConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/awp/**")
                .allowedOrigins("http://localhost:3000", "http://localhost:3001", "http://127.0.0.1:3000", "http://127.0.0.1:3001")
                .allowedHeaders("*")
                .allowedMethods("POST", "OPTIONS")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
