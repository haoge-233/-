package org.example.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * /api/** 的令牌鉴权过滤器
 *
 * <p>仅当 server.auth.token 配置了非空值时生效；留空表示不鉴权，
 * 此时应确保 server.address 仍为回环地址，否则等于对网络开放无认证的模型调用。</p>
 */
@Component
public class ApiTokenAuthFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(ApiTokenAuthFilter.class);

    private static final String HEADER_NAME = "X-API-Token";

    /** 免鉴权白名单：前端要先问"是否需要令牌"，才能发起带令牌的请求 */
    private static final Set<String> PUBLIC_PATHS = Set.of("/api/config");

    @Value("${server.auth.token:}")
    private String configuredToken;

    @Override
    protected void initFilterBean() {
        if (isAuthDisabled()) {
            logger.warn("接口鉴权未启用（server.auth.token 为空）。请确认 server.address 仍是 127.0.0.1，"
                    + "否则任何能访问该端口的人都可消耗你的模型额度。");
        } else {
            logger.info("接口鉴权已启用，/api/** 需要 {} 或 Authorization: Bearer 头", HEADER_NAME);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (PUBLIC_PATHS.contains(uri)) {
            return true;
        }
        if (isAuthDisabled()) {
            return true;
        }
        // 静态页面保持开放：前端需要先加载页面才能输入令牌
        return !uri.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String presented = request.getHeader(HEADER_NAME);
        if (presented == null || presented.isEmpty()) {
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) {
                presented = authorization.substring(7);
            }
        }

        if (presented != null && matches(presented)) {
            filterChain.doFilter(request, response);
            return;
        }

        logger.warn("拒绝未授权访问: {} {}", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"缺少或无效的访问令牌\",\"data\":null}");
    }

    private boolean isAuthDisabled() {
        return configuredToken == null || configuredToken.isBlank();
    }

    /**
     * 定长时间比较，避免通过响应时间逐字节猜测令牌
     */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                configuredToken.getBytes(StandardCharsets.UTF_8));
    }
}
