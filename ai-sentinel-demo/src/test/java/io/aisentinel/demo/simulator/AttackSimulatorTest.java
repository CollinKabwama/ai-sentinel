package io.aisentinel.demo.simulator;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttackSimulatorTest {

    @Test
    void usesServerPortFromEnvironment() {
        Environment env = mock(Environment.class);
        when(env.getProperty("server.port", Integer.class, 8080)).thenReturn(9090);
        AttackSimulator sim = new AttackSimulator(env);
        assertThat(ReflectionTestUtils.getField(sim, "port")).isEqualTo(9090);
    }
}
