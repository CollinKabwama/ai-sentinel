package dev.aisentinel.demo.simulator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Simulates normal and suspicious traffic patterns after startup.
 */
@Slf4j
@Component
public class AttackSimulator {

    private final int port;

    public AttackSimulator(Environment env) {
        this.port = env.getProperty("server.port", Integer.class, 8080);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runSimulation() {
        log.info("AttackSimulator: starting traffic simulation in 2 seconds...");
        new Thread(() -> {
            try {
                Thread.sleep(2000);
                simulateNormalTraffic();
                simulateSuspiciousTraffic();
            } catch (Exception e) {
                log.warn("AttackSimulator error", e);
            }
        }).start();
    }

    private void simulateNormalTraffic() throws Exception {
        log.info("AttackSimulator: normal traffic (5 requests)");
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var base = "http://localhost:" + port;
        for (int i = 0; i < 5; i++) {
            var req = HttpRequest.newBuilder(URI.create(base + "/api/hello")).GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            log.debug("Normal request {} -> {}", i + 1, resp.statusCode());
        }
    }

    private void simulateSuspiciousTraffic() throws Exception {
        log.info("AttackSimulator: suspicious traffic (burst + parameter tampering)");
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var base = "http://localhost:" + port;
        for (int i = 0; i < 20; i++) {
            var uri = URI.create(base + "/api/data?limit=100&x=" + i + "&y=" + i * 2);
            var req = HttpRequest.newBuilder(uri).GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            log.debug("Suspicious request {} -> {}", i + 1, resp.statusCode());
        }
    }
}
