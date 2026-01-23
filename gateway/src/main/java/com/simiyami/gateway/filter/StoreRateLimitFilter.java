package com.simiyami.gateway.filter;

import com.simiyami.gateway.config.StoreRateLimitConfig;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 가게별 Rate Limit 필터
 * - 기본값: 5000 req/min per store
 * - X-Store-Id 헤더로 가게 식별
 * - 동적으로 Rate Limit 조절 가능 (이벤트 시)
 */
@Component
public class StoreRateLimitFilter implements GlobalFilter, Ordered {

    private static final String STORE_ID_HEADER = "X-Store-Id";

    private final StoreRateLimitConfig rateLimitConfig;

    // 가게별 버킷 저장소
    private final Map<String, Bucket> storeBuckets = new ConcurrentHashMap<>();

    // 버킷 생성 시 사용된 limit 저장 (동적 limit 변경 감지용)
    private final Map<String, Long> bucketLimits = new ConcurrentHashMap<>();

    public StoreRateLimitFilter(StoreRateLimitConfig rateLimitConfig) {
        this.rateLimitConfig = rateLimitConfig;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String storeId = exchange.getRequest().getHeaders().getFirst(STORE_ID_HEADER);

        // X-Store-Id 헤더가 없으면 가게별 Rate Limit 적용하지 않음
        if (storeId == null || storeId.isBlank()) {
            return chain.filter(exchange);
        }

        long currentLimit = rateLimitConfig.getLimit(storeId);
        Bucket bucket = getOrCreateBucket(storeId, currentLimit);

        if (bucket.tryConsume(1)) {
            return chain.filter(exchange);
        } else {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().add("X-RateLimit-Type", "store");
            exchange.getResponse().getHeaders().add("X-RateLimit-Store-Id", storeId);
            exchange.getResponse().getHeaders().add("X-RateLimit-Retry-After", "60");
            return exchange.getResponse().setComplete();
        }
    }

    /**
     * 버킷 조회 또는 생성
     * - limit이 변경되면 새 버킷 생성 (동적 조절 지원)
     */
    private Bucket getOrCreateBucket(String storeId, long limit) {
        Long existingLimit = bucketLimits.get(storeId);

        // limit이 변경되었으면 버킷 재생성
        if (existingLimit != null && existingLimit != limit) {
            storeBuckets.remove(storeId);
            bucketLimits.remove(storeId);
        }

        bucketLimits.putIfAbsent(storeId, limit);
        return storeBuckets.computeIfAbsent(storeId, k -> createStoreBucket(limit));
    }

    /**
     * Token Bucket 생성
     * @param limit 분당 요청 제한
     */
    private Bucket createStoreBucket(long limit) {
        Bandwidth bandwidth = Bandwidth.classic(limit, Refill.greedy(limit, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(bandwidth).build();
    }

    @Override
    public int getOrder() {
        // RateLimitFilter(0) 이후에 실행
        return 1;
    }
}
