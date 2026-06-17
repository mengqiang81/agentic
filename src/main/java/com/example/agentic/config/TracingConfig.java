package com.example.agentic.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OTEL Tracing 配置。
 * <p>
 * Tracing 通过 OtelTracingMiddleware 注入到 HarnessAgent 的 Middleware 链（AgentConfig 中配置）。
 * 本配置类负责创建 OpenTelemetry SDK 实例，将 Trace 通过 OTLP/HTTP 导出到 Langfuse
 * 或 AgentScope Studio。
 * <p>
 * 端点须为完整 URL（含 /v1/traces）：
 *   - 通用 OTLP HTTP collector：http://localhost:4318/v1/traces
 *   - Langfuse OTLP ingest：http://localhost:3000/api/public/otel/v1/traces
 *     （Langfuse 还需 Basic Auth header：public_key:secret_key）
 * <p>
 * Span 层级：/awp/v1/runs → agent.run → model.call → tool.call
 */
@Configuration
public class TracingConfig {

    @Bean
    public OpenTelemetry openTelemetry(
            @Value("${otel.exporter.otlp.endpoint:http://localhost:4318/v1/traces}") String endpoint) {

        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .setResource(Resource.builder()
                        .put("service.name", "agentic-platform")
                        .build())
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();
    }
}
