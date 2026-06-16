package com.aryamann.ratelimiter.demo;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal downstream service the gateway protects. {@code /api/echo} reflects request
 * details; {@code /api/work} simulates a small amount of latency for load tests.
 */
@RestController
@RequestMapping("/api")
public class EchoController {

    @GetMapping("/echo")
    public Mono<Map<String, Object>> echo(ServerHttpRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "demo-backend");
        body.put("method", request.getMethod().name());
        body.put("path", request.getPath().value());
        body.put("timestamp", Instant.now().toString());
        body.put("headers", request.getHeaders().toSingleValueMap());
        return Mono.just(body);
    }

    @GetMapping("/work")
    public Mono<Map<String, Object>> work(@RequestParam(defaultValue = "20") long delayMs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "demo-backend");
        body.put("workedMs", delayMs);
        body.put("timestamp", Instant.now().toString());
        return Mono.just(body).delayElement(Duration.ofMillis(delayMs));
    }
}
