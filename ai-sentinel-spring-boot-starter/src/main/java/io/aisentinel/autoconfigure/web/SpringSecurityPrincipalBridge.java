package io.aisentinel.autoconfigure.web;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

/**
 * Resolves the authenticated principal name from Spring Security when it is on the classpath, without a hard
 * compile-time dependency on {@code spring-security-core}. Reflection targets are resolved once; the per-request path
 * only invokes cached {@link Method} handles.
 */
@Slf4j
final class SpringSecurityPrincipalBridge {

    private static final Bridge BRIDGE = Bridge.load();

    private SpringSecurityPrincipalBridge() {
    }

    /**
     * @return authenticated non-anonymous principal name, or {@code null} if Security is absent, not authenticated,
     * or resolution fails
     */
    static String resolveAuthenticatedPrincipalName() {
        return BRIDGE.resolve();
    }

    private static final class Bridge {
        private final Method getContext;
        private final Method getAuthentication;
        private final Method isAuthenticated;
        private final Method getPrincipal;
        private final Method getName;
        private final boolean active;

        private Bridge(Method getContext, Method getAuthentication, Method isAuthenticated, Method getPrincipal,
                       Method getName, boolean active) {
            this.getContext = getContext;
            this.getAuthentication = getAuthentication;
            this.isAuthenticated = isAuthenticated;
            this.getPrincipal = getPrincipal;
            this.getName = getName;
            this.active = active;
        }

        static Bridge load() {
            try {
                Class<?> holder = Class.forName("org.springframework.security.core.context.SecurityContextHolder");
                Class<?> securityContext = Class.forName("org.springframework.security.core.context.SecurityContext");
                Class<?> authentication = Class.forName("org.springframework.security.core.Authentication");
                Method getContext = holder.getMethod("getContext");
                Method getAuthentication = securityContext.getMethod("getAuthentication");
                Method isAuthenticated = authentication.getMethod("isAuthenticated");
                Method getPrincipal = authentication.getMethod("getPrincipal");
                Method getName = authentication.getMethod("getName");
                return new Bridge(getContext, getAuthentication, isAuthenticated, getPrincipal, getName, true);
            } catch (ReflectiveOperationException | LinkageError | SecurityException e) {
                // ReflectiveOperationException: ClassNotFoundException, NoSuchMethodException, etc.
                // LinkageError: includes NoClassDefFoundError and ExceptionInInitializerError from broken Security static init
                return noop();
            }
        }

        private static Bridge noop() {
            return new Bridge(null, null, null, null, null, false);
        }

        String resolve() {
            if (!active) {
                return null;
            }
            try {
                Object ctx = getContext.invoke(null);
                if (ctx == null) {
                    return null;
                }
                Object auth = getAuthentication.invoke(ctx);
                if (auth == null || !Boolean.TRUE.equals(isAuthenticated.invoke(auth))) {
                    return null;
                }
                Object principal = getPrincipal.invoke(auth);
                if (principal == null || "anonymousUser".equals(principal.toString())) {
                    return null;
                }
                Object name = getName.invoke(auth);
                return name != null ? name.toString() : null;
            } catch (ReflectiveOperationException e) {
                log.debug("SpringSecurityPrincipalBridge: failed to resolve principal, falling back to IP identity", e);
                return null;
            }
        }
    }
}
