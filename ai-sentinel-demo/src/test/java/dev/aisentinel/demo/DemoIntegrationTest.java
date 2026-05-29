package dev.aisentinel.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Test
    void helloReturnsOk() {
        var resp = rest.getForEntity("http://localhost:" + port + "/api/hello", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).contains("Hello from ai-sentinel demo");
    }

    @Test
    void actuatorHealthAndScoringWork() {
        var hello = rest.getForEntity("http://localhost:" + port + "/api/hello", String.class);
        assertThat(hello.getStatusCode().is2xxSuccessful()).isTrue();

        var health = rest.getForEntity("http://localhost:" + port + "/actuator/health", String.class);
        assertThat(health.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
