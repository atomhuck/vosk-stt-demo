package ru.example.interview.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Минимальная проверка готовности analytics service для локального запуска. */
@RestController
public class HealthController {
    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "interview-analytics");
    }
}
