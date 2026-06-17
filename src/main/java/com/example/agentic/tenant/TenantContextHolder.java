package com.example.agentic.tenant;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * 多租户 WebFilter。
 * <p>
 * 从 HTTP Header 提取 X-Tenant-Id / X-User-Id / X-Project-Id，注入到 Reactor Context，
 * 使得下游响应式链路中可以获取租户信息。
 * <p>
 * 当前隔离维度：tenantId + userId（构成 agentscope 的 userId 路由键）。
 * X-Project-Id 已预留解析位，但当前不参与隔离——后续升级 Project 级工作区时启用。
 * 详见 spec「未实现：Project 级工作区（设计备忘）」。
 */
@Component
public class TenantContextHolder implements WebFilter {

    public static final String TENANT_ID_KEY = "tenantId";
    public static final String USER_ID_KEY = "userId";
    /** 预留：Project 级工作区隔离维度（当前不参与 RuntimeContext 路由） */
    public static final String PROJECT_ID_KEY = "projectId";

    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final String USER_HEADER = "X-User-Id";
    private static final String PROJECT_HEADER = "X-Project-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String tenantId = exchange.getRequest().getHeaders().getFirst(TENANT_HEADER);
        String userId = exchange.getRequest().getHeaders().getFirst(USER_HEADER);
        String projectId = exchange.getRequest().getHeaders().getFirst(PROJECT_HEADER);

        return chain.filter(exchange)
                .contextWrite(ctx -> {
                    Context newCtx = ctx;
                    if (tenantId != null) {
                        newCtx = newCtx.put(TENANT_ID_KEY, tenantId);
                    }
                    if (userId != null) {
                        newCtx = newCtx.put(USER_ID_KEY, userId);
                    }
                    if (projectId != null) {
                        newCtx = newCtx.put(PROJECT_ID_KEY, projectId);
                    }
                    return newCtx;
                });
    }

    /**
     * 从 Reactor Context 中提取 tenantId
     */
    public static Mono<String> getTenantId() {
        return Mono.deferContextual(ctx ->
                Mono.justOrEmpty(ctx.getOrDefault(TENANT_ID_KEY, "default")));
    }

    /**
     * 从 Reactor Context 中提取 userId
     */
    public static Mono<String> getUserId() {
        return Mono.deferContextual(ctx ->
                Mono.justOrEmpty(ctx.getOrDefault(USER_ID_KEY, "anonymous")));
    }

    /**
     * 从 Reactor Context 中提取 projectId（预留，当前未参与隔离）。
     * 后续启用 Project 级工作区时，此值将编入 agentscope 的 userId 路由键。
     */
    public static Mono<String> getProjectId() {
        return Mono.deferContextual(ctx ->
                Mono.justOrEmpty(ctx.getOrDefault(PROJECT_ID_KEY, null)));
    }
}
