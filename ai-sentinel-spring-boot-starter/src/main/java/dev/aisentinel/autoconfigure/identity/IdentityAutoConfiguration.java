package dev.aisentinel.autoconfigure.identity;

import dev.aisentinel.autoconfigure.config.SentinelAutoConfiguration;
import dev.aisentinel.autoconfigure.config.SentinelProperties;
import dev.aisentinel.core.identity.trust.BehavioralBaselineStore;
import dev.aisentinel.core.identity.trust.BehavioralIdentityTrustEvaluator;
import dev.aisentinel.core.identity.trust.IdentityBehavioralBaselineStore;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.autoconfigure.identity.trust.RedisFailOpenBehavioralBaselineStore;
import dev.aisentinel.core.identity.spi.AuthenticationInspector;
import dev.aisentinel.core.identity.spi.IdentityContextResolver;
import dev.aisentinel.core.identity.spi.IdentityResponseHook;
import dev.aisentinel.core.identity.spi.NoopIdentityContextResolver;
import dev.aisentinel.core.identity.spi.NoopIdentityResponseHook;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.identity.spi.SessionInspector;
import dev.aisentinel.core.identity.spi.TrustEvaluator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import lombok.extern.slf4j.Slf4j;

/**
 * Identity resolution and hooks: conditional beans for {@link dev.aisentinel.core.model.RequestContext} population.
 * When {@code ai.sentinel.identity.enabled=false} (default), no-op implementations preserve existing API security behavior.
 */
@AutoConfiguration(before = SentinelAutoConfiguration.class)
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "ai.sentinel.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SentinelProperties.class)
@Slf4j
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
     * Behavioral baselines (in-memory, or Redis-backed with fail-open when
     * {@link SentinelProperties.Trust#getDistributed()} is enabled and {@link StringRedisTemplate} exists).
     */
    @Bean
    @ConditionalOnMissingBean(BehavioralBaselineStore.class)
    @ConditionalOnExpression("'${ai.sentinel.identity.enabled:false}'.equals('true') "
        + "&& '${ai.sentinel.identity.trust.trust-evaluation-enabled:true}'.equals('true')")
    public BehavioralBaselineStore aisentinelBehavioralBaselineStore(SentinelProperties sentinelProperties,
                                                                      ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                                                      ObjectProvider<SentinelMetrics> sentinelMetricsProvider) {
        SentinelProperties.Trust t = sentinelProperties.getIdentity().getTrust();
        IdentityBehavioralBaselineStore memory = new IdentityBehavioralBaselineStore(t.getBaselineTtl(), t.getBaselineMaxKeys());
        if (!t.getDistributed().isEnabled()) {
            return memory;
        }
        StringRedisTemplate tpl = redisTemplateProvider.getIfAvailable();
        if (tpl == null) {
            log.warn(
                "ai.sentinel.identity.trust.distributed.enabled=true but no StringRedisTemplate bean is available; "
                    + "using in-memory behavioral baselines only (no cross-instance continuity).");
            return memory;
        }
        SentinelMetrics metrics = sentinelMetricsProvider.getIfAvailable();
        if (metrics == null) {
            metrics = SentinelMetrics.NOOP;
        }
        return new RedisFailOpenBehavioralBaselineStore(tpl, memory, sentinelProperties, metrics);
    }

    /**
     * {@link BehavioralIdentityTrustEvaluator} when identity and trust evaluation are enabled;
     * otherwise {@link NoopTrustEvaluator} (trust from resolver only). Applications may replace with a custom
     * {@link TrustEvaluator}.
     */
    @Bean
    @ConditionalOnMissingBean(TrustEvaluator.class)
    @ConditionalOnExpression("'${ai.sentinel.identity.enabled:false}'.equals('true') "
        + "&& '${ai.sentinel.identity.trust.trust-evaluation-enabled:true}'.equals('true')")
    public TrustEvaluator aisentinelBehavioralTrustEvaluator(BehavioralBaselineStore aisentinelBehavioralBaselineStore,
                                                             SentinelProperties sentinelProperties) {
        SentinelProperties.Trust t = sentinelProperties.getIdentity().getTrust();
        return new BehavioralIdentityTrustEvaluator(
            aisentinelBehavioralBaselineStore,
            t.getBurstRequestsThreshold(),
            t.getSparseHistoryTrustCap(),
            t.getMaxTotalPenalty());
    }

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
