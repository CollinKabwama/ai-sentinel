package io.aisentinel.autoconfigure.web;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.core.SentinelPipeline;
import io.aisentinel.core.metrics.SentinelMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Servlet filter that runs the {@link io.aisentinel.core.SentinelPipeline} once per request (after auth when ordered late).
 * Respects {@link io.aisentinel.autoconfigure.config.SentinelProperties#getExcludePaths()} and mode OFF/MONITOR/ENFORCE.
 */
@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@RequiredArgsConstructor
public class SentinelFilter extends OncePerRequestFilter {

    private final SentinelPipeline pipeline;
    private final SentinelProperties props;
    private final SentinelMetrics metrics;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!props.isEnabled() || props.getMode() == SentinelProperties.Mode.OFF) {
            filterChain.doFilter(request, response);
            return;
        }

        if (shouldExclude(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String identityHash = resolveIdentityHash(request);

        try {
            boolean proceed = pipeline.process(request, response, identityHash);

            if (props.getMode() == SentinelProperties.Mode.MONITOR) {
                filterChain.doFilter(request, response);
                return;
            }

            if (!proceed) {
                log.debug("Request blocked by Sentinel for path={} identity={}", request.getRequestURI(), maskHash(identityHash));
                return;
            }
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.warn("Sentinel pipeline error for path={}, allowing request: {}", request.getRequestURI(), e.getMessage());
            metrics.recordFailOpen();
            filterChain.doFilter(request, response);
        }
    }

    private boolean shouldExclude(String path) {
        if (path == null) return true;
        for (String pattern : props.getExcludePaths()) {
            if (matchPattern(pattern, path)) return true;
        }
        return false;
    }

    private boolean matchPattern(String pattern, String path) {
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        return path.equals(pattern);
    }

    private String resolveIdentityHash(HttpServletRequest request) {
        String identity = ClientIpResolver.resolveClientIp(request, props.getTrustedProxies());
        try {
            Object ctx = Class.forName("org.springframework.security.core.context.SecurityContextHolder")
                .getMethod("getContext").invoke(null);
            if (ctx != null) {
                Object auth = ctx.getClass().getMethod("getAuthentication").invoke(ctx);
                if (auth != null && Boolean.TRUE.equals(auth.getClass().getMethod("isAuthenticated").invoke(auth))) {
                    Object principal = auth.getClass().getMethod("getPrincipal").invoke(auth);
                    if (principal != null && !"anonymousUser".equals(principal.toString())) {
                        identity = auth.getClass().getMethod("getName").invoke(auth).toString();
                    }
                }
            }
        } catch (Exception ignored) { }
        return hash(identity);
    }

    /**
     * @deprecated use {@link ClientIpResolver#resolveClientIp(HttpServletRequest, java.util.List)}
     */
    @Deprecated
    static String resolveClientIp(HttpServletRequest request, java.util.List<String> trustedProxyEntries) {
        return ClientIpResolver.resolveClientIp(request, trustedProxyEntries);
    }

    private static String hash(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((s != null ? s : "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf((s != null ? s : "").hashCode());
        }
    }

    private static String maskHash(String h) {
        if (h == null || h.length() < 8) return "***";
        return h.substring(0, 4) + "***" + h.substring(h.length() - 4);
    }
}
