package dev.aisentinel.validation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal HTTP endpoint for distributed enforcement validation tests (MockMvc with {@link dev.aisentinel.autoconfigure.web.SentinelFilter}).
 */
@RestController
public class DistributedEnforcementPingController {

    @GetMapping("/api/distributed-validation/ping")
    public String ping() {
        return "ok";
    }
}
