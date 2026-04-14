package io.aisentinel.autoconfigure.identity;

import io.aisentinel.core.identity.model.AuthenticationContext;
import io.aisentinel.core.identity.spi.AuthenticationInspector;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

/**
 * Resolves authentication via Spring Security when present on the classpath (reflection; no compile-time dependency).
 * Reflective {@link Method} handles are resolved once at class initialization; the request path only invokes them.
 */
@Slf4j
public final class SpringSecurityAuthenticationInspector implements AuthenticationInspector {

    private static final boolean SPRING_SECURITY_REFLECTION_AVAILABLE;
    private static final Method HOLDER_GET_CONTEXT;
    private static final Method CONTEXT_GET_AUTHENTICATION;
    private static final Method AUTH_IS_AUTHENTICATED;
    private static final Method AUTH_GET_PRINCIPAL;
    private static final Method AUTH_GET_NAME;

    static {
        Method getContext = null;
        Method getAuthentication = null;
        Method isAuthenticated = null;
        Method getPrincipal = null;
        Method getName = null;
        boolean available = false;
        try {
            Class<?> holderClass = Class.forName("org.springframework.security.core.context.SecurityContextHolder");
            Class<?> securityContextClass = Class.forName("org.springframework.security.core.context.SecurityContext");
            Class<?> authenticationClass = Class.forName("org.springframework.security.core.Authentication");
            getContext = holderClass.getMethod("getContext");
            getAuthentication = securityContextClass.getMethod("getAuthentication");
            isAuthenticated = authenticationClass.getMethod("isAuthenticated");
            getPrincipal = authenticationClass.getMethod("getPrincipal");
            getName = authenticationClass.getMethod("getName");
            available = true;
        } catch (Throwable t) {
            log.debug("Spring Security not available for AuthenticationInspector: {}", t.toString());
        }
        SPRING_SECURITY_REFLECTION_AVAILABLE = available;
        HOLDER_GET_CONTEXT = getContext;
        CONTEXT_GET_AUTHENTICATION = getAuthentication;
        AUTH_IS_AUTHENTICATED = isAuthenticated;
        AUTH_GET_PRINCIPAL = getPrincipal;
        AUTH_GET_NAME = getName;
    }

    @Override
    public AuthenticationContext inspect(HttpServletRequest request, String identityHash) {
        if (!SPRING_SECURITY_REFLECTION_AVAILABLE) {
            return AuthenticationContext.unauthenticated();
        }
        try {
            Object securityContext = HOLDER_GET_CONTEXT.invoke(null);
            if (securityContext == null) {
                return AuthenticationContext.unauthenticated();
            }
            Object auth = CONTEXT_GET_AUTHENTICATION.invoke(securityContext);
            if (auth == null || !Boolean.TRUE.equals(AUTH_IS_AUTHENTICATED.invoke(auth))) {
                return AuthenticationContext.unauthenticated();
            }
            Object principal = AUTH_GET_PRINCIPAL.invoke(auth);
            if (principal == null || "anonymousUser".equals(principal.toString())) {
                return AuthenticationContext.unauthenticated();
            }
            String name = AUTH_GET_NAME.invoke(auth).toString();
            return AuthenticationContext.ofPrincipal(name);
        } catch (ReflectiveOperationException e) {
            log.debug("Spring Security authentication inspection failed: {}", e.toString());
            return AuthenticationContext.unauthenticated();
        }
    }
}
