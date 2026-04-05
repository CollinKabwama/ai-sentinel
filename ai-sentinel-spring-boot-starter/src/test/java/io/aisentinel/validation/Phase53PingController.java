package io.aisentinel.validation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal endpoint for Phase 5.3 distributed enforcement validation (MockMvc + {@link io.aisentinel.autoconfigure.web.SentinelFilter}).
 */
@RestController
public class Phase53PingController {

    @GetMapping("/api/phase53-ping")
    public String ping() {
        return "ok";
    }
}
