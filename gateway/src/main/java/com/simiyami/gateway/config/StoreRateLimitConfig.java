package com.simiyami.gateway.config;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 가게별 Rate Limit 설정 저장소
 * - 기본값: 5000 req/min
 * - 이벤트 시 동적으로 조절 가능
 */
@Component
public class StoreRateLimitConfig {

    private static final long DEFAULT_STORE_LIMIT = 5000;

    // 가게별 커스텀 Rate Limit 저장소
    private final Map<String, Long> storeLimits = new ConcurrentHashMap<>();

    /**
     * 특정 가게의 Rate Limit 조회
     * @param storeId 가게 ID
     * @return Rate Limit (기본값 5000)
     */
    public long getLimit(String storeId) {
        return storeLimits.getOrDefault(storeId, DEFAULT_STORE_LIMIT);
    }

    /**
     * 특정 가게의 Rate Limit 설정 (이벤트 시 동적 조절)
     * @param storeId 가게 ID
     * @param limit 새로운 Rate Limit
     */
    public void setLimit(String storeId, long limit) {
        storeLimits.put(storeId, limit);
    }

    /**
     * 특정 가게의 Rate Limit을 기본값으로 복원
     * @param storeId 가게 ID
     */
    public void resetToDefault(String storeId) {
        storeLimits.remove(storeId);
    }

    /**
     * 기본 Rate Limit 조회
     * @return 기본 Rate Limit (5000)
     */
    public long getDefaultLimit() {
        return DEFAULT_STORE_LIMIT;
    }

    /**
     * 모든 커스텀 Rate Limit 조회
     * @return 가게별 커스텀 Rate Limit 맵
     */
    public Map<String, Long> getAllCustomLimits() {
        return new ConcurrentHashMap<>(storeLimits);
    }
}
