package io.aisentinel.autoconfigure.identity;

import io.aisentinel.autoconfigure.config.SentinelAutoConfiguration;
import io.aisentinel.core.identity.spi.AuthenticationInspector;
import io.aisentinel.core.identity.spi.IdentityContextResolver;
import io.aisentinel.core.identity.spi.IdentityResponseHook;
import io.aisentinel.core.identity.spi.NoopIdentityContextResolver;
import io.aisentinel.core.identity.spi.NoopIdentityResponseHook;
import io.aisentinel.core.identity.spi.NoopTrustEvaluator;
import io.aisentinel.core.identity.spi.SessionInspector;
import io.aisentinel.core.identity.spi.TrustEvaluator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Phase 0 identity foundation: conditional beans for {@link io.aisentinel.core.model.RequestContext} population and hooks.
 * When {@code ai.sentinel.identity.enabled=false} (default), no-op implementations preserve existing API security behavior.
 */
@AutoConfiguration(before = SentinelAutoConfiguration.class)
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "ai.sentinel.enabled", havingValue = "true", matchIfMissing = true)
public class IdentityAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "ai.sentinel.identity.enabled", havingValue = "true")
    @ConditionalOnMissingBean(AuthenticationInspector.class)
    public AuthenticationInspector aisentinelAuthenticationInspector() {
        return new SpringSecurityAuthenticationInspector();
    }

    @Bean
    @ConditionalOnProperty(name = "ai.sentinel.identity.enabled", havingValue = "true")
    @ConditionalOnMissingBean(SessionInspector.class)
    public SessionInspector aisentinelSessionInspector() {
        return new HttpSessionSessionInspector();
    }

    @Bean
    @ConditionalOnProperty(name = "ai.sentinel.identity.enabled", havingValue = "true")
    @ConditionalOnMissingBean(IdentityContextResolver.class)
    public IdentityContextResolver aisentinelIdentityContextResolver(AuthenticationInspector aisentinelAuthenticationInspector,
                                                                   SessionInspector aisentinelSessionInspector) {
        return new ServletIdentityContextResolver(aisentinelAuthenticationInspector, aisentinelSessionInspector);
    }

    @Bean
    @ConditionalOnProperty(name = "ai.sentinel.identity.enabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(IdentityContextResolver.class)
    public IdentityContextResolver aisentinelNoopIdentityContextResolver() {
        return NoopIdentityContextResolver.INSTANCE;
    }

    /**
     * Phase 0: always {@link NoopTrustEvaluator} unless the application supplies its own {@link TrustEvaluator}.
     * Identity trust therefore remains whatever {@link IdentityContextResolver} stored (typically baseline from
     * {@link ServletIdentityContextResolver}).
     */
    @Bean
    @ConditionalOnMissingBean(TrustEvaluator.class)
    public TrustEvaluator aisentinelDefaultTrustEvaluator() {
        return NoopTrustEvaluator.INSTANCE;
    }

    @Bean
    @ConditionalOnMissingBean(IdentityResponseHook.class)
    public IdentityResponseHook aisentinelIdentityResponseHook() {
        return NoopIdentityResponseHook.INSTANCE;
    }
}
