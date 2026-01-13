package com.simiyami.gateway.filter;

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

@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    // 사용자별 버킷 저장소
    private final Map<String, Bucket> userBuckets = new ConcurrentHashMap<>();

    // IP별 버킷 저장소
    private final Map<String, Bucket> ipBuckets = new ConcurrentHashMap<>();

    // 글로벌 버킷 (인증되지 않은 요청용)
    private final Bucket globalBucket = createGlobalBucket();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 사용자 ID 추출 (JWT에서 sub claim)
        String userId = extractUserId(exchange);
        String clientIp = extractClientIp(exchange);

        Bucket bucket;
        String bucketType;

        if (userId != null) {
            // 인증된 사용자: 사용자별 Rate Limit (100 req/min)
            bucket = userBuckets.computeIfAbsent(userId, k -> createUserBucket());
            bucketType = "user";
        } else if (clientIp != null) {
            // 미인증 사용자: IP별 Rate Limit (1000 req/min)
            bucket = ipBuckets.computeIfAbsent(clientIp, k -> createIpBucket());
            bucketType = "ip";
        } else {
            // 글로벌 Rate Limit
            bucket = globalBucket;
            bucketType = "global";
        }

        if (bucket.tryConsume(1)) {
            return chain.filter(exchange);
        } else {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().add("X-RateLimit-Type", bucketType);
            exchange.getResponse().getHeaders().add("X-RateLimit-Retry-After", "60");
            return exchange.getResponse().setComplete();
        }
    }

    private String extractUserId(ServerWebExchange exchange) {
        // JWT 토큰에서 사용자 ID 추출 시도
        // SecurityContext에서 Principal 확인
        return exchange.getPrincipal()
            .map(principal -> principal.getName())
            .block();
    }

    private String extractClientIp(ServerWebExchange exchange) {
        // X-Forwarded-For 헤더 확인 (프록시/로드밸런서 뒤에 있는 경우)
        String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        // 직접 연결된 클라이언트 IP
        if (exchange.getRequest().getRemoteAddress() != null) {
            return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        }

        return null;
    }

    // 사용자별: 100 req/min (Token Bucket)
    private Bucket createUserBucket() {
        Bandwidth limit = Bandwidth.classic(100, Refill.greedy(100, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    // IP별: 1000 req/min (Token Bucket)
    private Bucket createIpBucket() {
        Bandwidth limit = Bandwidth.classic(1000, Refill.greedy(1000, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    // 글로벌: 5000 req/min (Token Bucket)
    private Bucket createGlobalBucket() {
        Bandwidth limit = Bandwidth.classic(5000, Refill.greedy(5000, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
