package dev.aisentinel.autoconfigure.web;

import dev.aisentinel.autoconfigure.config.SentinelProperties;
import dev.aisentinel.core.SentinelPipeline;
import dev.aisentinel.core.enforcement.DiscardingEnforcementResponse;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.metrics.FailOpenReason;
import dev.aisentinel.core.metrics.SentinelMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that runs the {@link dev.aisentinel.core.SentinelPipeline} once per request (after auth when ordered late).
 * Respects configured exclude paths and mode OFF/MONITOR/ENFORCE.
 * <p>
 * In {@link SentinelProperties.Mode#MONITOR}, enforcement writes are discarded so a custom
 * {@link dev.aisentinel.core.enforcement.EnforcementHandler} cannot double-write the client response while the
 * filter always continues the chain.
 */
@Slf4j
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
            EnforcementResponse enforcementResponse = props.getMode() == SentinelProperties.Mode.MONITOR
                ? DiscardingEnforcementResponse.INSTANCE
                : new ServletEnforcementResponse(response);

            boolean proceed = pipeline.process(
                new ServletHttpRequestView(request),
                enforcementResponse,
                identityHash);

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
            log.warn("Sentinel pipeline error for path={}, allowing request (fail-open reason={}): {}: {}",
                request.getRequestURI(), FailOpenReason.FILTER_FAILURE,
                e.getClass().getSimpleName(), e.getMessage());
            metrics.recordFailOpen(FailOpenReason.FILTER_FAILURE);
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
        String principalName = SpringSecurityPrincipalBridge.resolveAuthenticatedPrincipalName();
        if (principalName != null) {
            identity = principalName;
        }
        return IdentityHasher.sha256Hex(identity);
    }

    private static String maskHash(String h) {
        if (h == null || h.length() < 8) return "***";
        return h.substring(0, 4) + "***" + h.substring(h.length() - 4);
    }
}
