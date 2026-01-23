package com.simiyami.gateway.controller;

import com.simiyami.gateway.config.StoreRateLimitConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Rate Limit 동적 조절 Admin API
 * - 이벤트 시 특정 가게의 Rate Limit을 조절할 수 있음
 * - admin 역할 필요
 */
@RestController
@RequestMapping("/admin/rate-limit")
public class RateLimitAdminController {

    private final StoreRateLimitConfig rateLimitConfig;

    public RateLimitAdminController(StoreRateLimitConfig rateLimitConfig) {
        this.rateLimitConfig = rateLimitConfig;
    }

    /**
     * 모든 가게의 Rate Limit 설정 조회
     */
    @GetMapping("/stores")
    public Mono<ResponseEntity<Map<String, Object>>> getAllStoreLimits() {
        return Mono.just(ResponseEntity.ok(Map.of(
            "defaultLimit", rateLimitConfig.getDefaultLimit(),
            "customLimits", rateLimitConfig.getAllCustomLimits()
        )));
    }

    /**
     * 특정 가게의 Rate Limit 조회
     */
    @GetMapping("/stores/{storeId}")
    public Mono<ResponseEntity<Map<String, Object>>> getStoreLimit(@PathVariable String storeId) {
        return Mono.just(ResponseEntity.ok(Map.of(
            "storeId", storeId,
            "limit", rateLimitConfig.getLimit(storeId),
            "isCustom", rateLimitConfig.getAllCustomLimits().containsKey(storeId)
        )));
    }

    /**
     * 특정 가게의 Rate Limit 변경 (이벤트 시 동적 조절)
     */
    @PutMapping("/stores/{storeId}")
    public Mono<ResponseEntity<Map<String, Object>>> setStoreLimit(
            @PathVariable String storeId,
            @RequestBody Map<String, Long> request) {

        Long limit = request.get("limit");
        if (limit == null || limit <= 0) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(
                "error", "Bad Request",
                "message", "limit must be a positive number"
            )));
        }

        rateLimitConfig.setLimit(storeId, limit);
        return Mono.just(ResponseEntity.ok(Map.of(
            "storeId", storeId,
            "limit", limit,
            "message", "Rate limit updated successfully"
        )));
    }

    /**
     * 특정 가게의 Rate Limit을 기본값으로 복원
     */
    @DeleteMapping("/stores/{storeId}")
    public Mono<ResponseEntity<Map<String, Object>>> resetStoreLimit(@PathVariable String storeId) {
        rateLimitConfig.resetToDefault(storeId);
        return Mono.just(ResponseEntity.ok(Map.of(
            "storeId", storeId,
            "limit", rateLimitConfig.getDefaultLimit(),
            "message", "Rate limit reset to default"
        )));
    }
}
