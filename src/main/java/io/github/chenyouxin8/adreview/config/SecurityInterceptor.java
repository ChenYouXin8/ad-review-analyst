package io.github.chenyouxin8.adreview.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 接口鉴权拦截器
 *
 * 验证请求头中的 Bearer Token：
 * - 请求头格式：Authorization: Bearer <token>
 * - 未配置 api-key 时跳过验证（开发模式）
 */
@Component
@Slf4j
public class SecurityInterceptor implements HandlerInterceptor {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${spring.security.api-key:}")
    private String configuredApiKey;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();

        if (path.startsWith("/api/swagger") ||
            path.startsWith("/api/v3/api-docs") ||
            path.startsWith("/api/webjars") ||
            path.startsWith("/api/error") ||
            "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        if (configuredApiKey == null || configuredApiKey.isBlank()) {
            return true;
        }

        String authHeader = request.getHeader(AUTH_HEADER);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response, "缺少 Authorization 头或格式错误");
            return false;
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (!configuredApiKey.equals(token)) {
            log.warn("鉴权失败，来源 IP={}", request.getRemoteAddr());
            writeUnauthorized(response, "Token 无效");
            return false;
        }

        return true;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":40100,\"message\":\"" + message + "\",\"data\":null}");
    }
}
