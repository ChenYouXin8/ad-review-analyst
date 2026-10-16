package io.github.chenyouxin8.adreview.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 轻量级请求可观测性过滤器。
 *
 * <p>为每个请求生成 8 位短 {@code traceId} 写入 SLF4J {@link MDC}（配合日志 pattern 中的
 * {@code [%X{traceId:-}]} 串联全部业务日志与 AI 调用日志），并通过响应头 {@code X-Trace-Id} 返回；
 * 请求结束时打印一行包含 URI / Method / Status / 耗时 的访问日志，并清理 MDC。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class TraceLogFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        MDC.put(TRACE_ID_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long cost = System.currentTimeMillis() - start;
            log.info("[{}] {} {} -> {} ({} ms)",
                    traceId, request.getMethod(), request.getRequestURI(), response.getStatus(), cost);
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
