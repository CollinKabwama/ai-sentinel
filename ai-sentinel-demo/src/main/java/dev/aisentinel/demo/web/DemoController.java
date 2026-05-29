package dev.aisentinel.demo.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class DemoController {

    @GetMapping("/hello")
    public Map<String, String> hello() {
        log.trace("GET /api/hello");
        return Map.of("message", "Hello from ai-sentinel demo");
    }

    @GetMapping("/data")
    public Map<String, Object> data(@RequestParam(defaultValue = "10") int limit) {
        log.trace("GET /api/data limit={}", limit);
        return Map.of("limit", limit, "items", "[]");
    }

    @PostMapping("/submit")
    public Map<String, String> submit(@RequestBody Map<String, Object> body) {
        log.trace("POST /api/submit keys={}", body.size());
        return Map.of("status", "received", "keys", String.valueOf(body.size()));
    }
}
