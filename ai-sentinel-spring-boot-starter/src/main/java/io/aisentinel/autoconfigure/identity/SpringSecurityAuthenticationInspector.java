package io.aisentinel.autoconfigure.identity;

import io.aisentinel.core.identity.model.AuthenticationContext;
import io.aisentinel.core.identity.spi.AuthenticationInspector;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Resolves authentication via Spring Security when present on the classpath (reflection; no compile-time dependency).
 * Reflective {@link Method} handles are resolved once at class initialization; the request path only invokes them.
 */
@Slf4j
public final class SpringSecurityAuthenticationInspector implements AuthenticationInspector {

    private static final int MAX_ROLE_NAMES = 32;

    private static final boolean SPRING_SECURITY_REFLECTION_AVAILABLE;
    private static final Method HOLDER_GET_CONTEXT;
    private static final Method CONTEXT_GET_AUTHENTICATION;
    private static final Method AUTH_IS_AUTHENTICATED;
    private static final Method AUTH_GET_PRINCIPAL;
    private static final Method AUTH_GET_NAME;
    private static final Method AUTH_GET_AUTHORITIES;
    private static final Method GRANTED_AUTHORITY_GET_AUTHORITY;

    static {
        Method getContext = null;
        Method getAuthentication = null;
        Method isAuthenticated = null;
        Method getPrincipal = null;
        Method getName = null;
        Method getAuthorities = null;
        Method gaGetAuthority = null;
        boolean available = false;
        try {
            Class<?> holderClass = Class.forName("org.springframework.security.core.context.SecurityContextHolder");
            Class<?> securityContextClass = Class.forName("org.springframework.security.core.context.SecurityContext");
            Class<?> authenticationClass = Class.forName("org.springframework.security.core.Authentication");
            Class<?> grantedAuthorityClass = Class.forName("org.springframework.security.core.GrantedAuthority");
            getContext = holderClass.getMethod("getContext");
            getAuthentication = securityContextClass.getMethod("getAuthentication");
            isAuthenticated = authenticationClass.getMethod("isAuthenticated");
            getPrincipal = authenticationClass.getMethod("getPrincipal");
            getName = authenticationClass.getMethod("getName");
            getAuthorities = authenticationClass.getMethod("getAuthorities");
            gaGetAuthority = grantedAuthorityClass.getMethod("getAuthority");
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
        AUTH_GET_AUTHORITIES = getAuthorities;
        GRANTED_AUTHORITY_GET_AUTHORITY = gaGetAuthority;
    }

    @Override
    public AuthenticationContext inspect(HttpServletRequest request, String identityHash) {
        if (!SPRING_SECURITY_REFLECTION_AVAILABLE) {
            return AuthenticationContext.unauthenticated(false);
        }
        try {
            Object securityContext = HOLDER_GET_CONTEXT.invoke(null);
            if (securityContext == null) {
                return AuthenticationContext.unauthenticated(true);
            }
            Object auth = CONTEXT_GET_AUTHENTICATION.invoke(securityContext);
            if (auth == null || !Boolean.TRUE.equals(AUTH_IS_AUTHENTICATED.invoke(auth))) {
                return AuthenticationContext.unauthenticated(true);
            }
            Object principal = AUTH_GET_PRINCIPAL.invoke(auth);
            if (principal == null || "anonymousUser".equals(principal.toString())) {
                return AuthenticationContext.unauthenticated(true);
            }
            Object nameObj = AUTH_GET_NAME.invoke(auth);
            String name = nameObj != null ? nameObj.toString() : "";
            if (name.isEmpty()) {
                return AuthenticationContext.unauthenticated(true);
            }
            String authType = auth.getClass().getSimpleName();
            List<String> roles = extractRoleNames(auth);
            return AuthenticationContext.ofAuthenticated(name, authType, roles);
        } catch (ReflectiveOperationException e) {
            log.debug("Spring Security authentication inspection failed: {}", e.toString());
            return AuthenticationContext.unauthenticated(true);
        }
    }

    private static List<String> extractRoleNames(Object auth) throws ReflectiveOperationException {
        if (AUTH_GET_AUTHORITIES == null || GRANTED_AUTHORITY_GET_AUTHORITY == null) {
            return List.of();
        }
        Object raw = AUTH_GET_AUTHORITIES.invoke(auth);
        if (!(raw instanceof Collection<?> col) || col.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(Math.min(col.size(), MAX_ROLE_NAMES));
        for (Object ga : col) {
            if (ga == null || out.size() >= MAX_ROLE_NAMES) {
                break;
            }
            Object r = GRANTED_AUTHORITY_GET_AUTHORITY.invoke(ga);
            if (r != null) {
                String s = r.toString();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return List.copyOf(out);
    }
}
