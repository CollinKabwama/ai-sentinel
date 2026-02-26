package io.aisentinel.autoconfigure.actuator;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.core.enforcement.CompositeEnforcementHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Registers the Sentinel actuator endpoint.
 * Loads after WebEndpointAutoConfiguration so the endpoint infrastructure is ready.
 */
@Slf4j
@AutoConfiguration(after = WebEndpointAutoConfiguration.class)
@ConditionalOnWebApplication
@ConditionalOnBean(SentinelProperties.class)
@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
public class SentinelEndpointAutoConfiguration {

    @Bean
    @ConditionalOnBean(CompositeEnforcementHandler.class)
    public SentinelActuatorEndpoint sentinelActuatorEndpoint(SentinelProperties props,
                                                            CompositeEnforcementHandler enforcementHandlerImpl) {
        log.debug("Registering Sentinel actuator endpoint");
        return new SentinelActuatorEndpoint(props, enforcementHandlerImpl);
    }
}
