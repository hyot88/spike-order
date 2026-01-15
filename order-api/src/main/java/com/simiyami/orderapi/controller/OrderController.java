package com.simiyami.orderapi.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "order-api"));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        Map<String, Object> response = new HashMap<>();
        response.put("userId", jwt.getSubject() != null ? jwt.getSubject() : jwt.getId());
        response.put("username", jwt.getClaimAsString("preferred_username"));
        response.put("email", jwt.getClaimAsString("email"));
        response.put("idempotencyKey", idempotencyKey != null ? idempotencyKey : "not provided");
        response.put("traceId", traceId != null ? traceId : "not provided");

        return ResponseEntity.ok(response);
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testPost(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestBody(required = false) Map<String, Object> body) {

        Map<String, Object> response = new HashMap<>();
        response.put("message", "POST request successful");
        response.put("userId", jwt.getSubject() != null ? jwt.getSubject() : jwt.getId());
        response.put("idempotencyKey", idempotencyKey);
        response.put("traceId", traceId != null ? traceId : "not provided");
        response.put("body", body != null ? body : Map.of());

        return ResponseEntity.ok(response);
    }
}
