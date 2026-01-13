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

import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    // 사용자별 버킷 저장소
    private final Map<String, Bucket> userBuckets = new ConcurrentHashMap<>();

    // IP별 버킷 저장소
    private final Map<String, Bucket> ipBuckets = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
            .map(Principal::getName)
            .map(Optional::ofNullable)
            .defaultIfEmpty(Optional.empty())
            .flatMap(optionalUserId -> {
                Bucket bucket;
                String bucketType;

                if (optionalUserId.isPresent() && !optionalUserId.get().isEmpty()) {
                    // 인증된 사용자: 사용자별 Rate Limit (100 req/min)
                    String userId = optionalUserId.get();
                    bucket = userBuckets.computeIfAbsent(userId, k -> createUserBucket());
                    bucketType = "user";
                } else {
                    // 미인증 사용자: IP별 Rate Limit (1000 req/min)
                    String clientIp = extractClientIp(exchange);
                    bucket = ipBuckets.computeIfAbsent(clientIp, k -> createIpBucket());
                    bucketType = "ip";
                }

                if (bucket.tryConsume(1)) {
                    return chain.filter(exchange);
                } else {
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    exchange.getResponse().getHeaders().add("X-RateLimit-Type", bucketType);
                    exchange.getResponse().getHeaders().add("X-RateLimit-Retry-After", "60");
                    return exchange.getResponse().setComplete();
                }
            });
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

        return "unknown";
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

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
