package com.simiyami.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class IdempotencyKeyFilter implements GlobalFilter, Ordered {

    private static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();

        // POST, PUT, PATCH 요청에 대해서만 멱등키 검증
        if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            String idempotencyKey = exchange.getRequest().getHeaders().getFirst(IDEMPOTENCY_KEY_HEADER);

            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                exchange.getResponse().getHeaders().add("X-Error-Message", "X-Idempotency-Key header is required for " + method + " requests");
                return exchange.getResponse().setComplete();
            }

            // UUID 형식 검증
            if (!isValidUUID(idempotencyKey)) {
                exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                exchange.getResponse().getHeaders().add("X-Error-Message", "X-Idempotency-Key must be a valid UUID");
                return exchange.getResponse().setComplete();
            }
        }

        return chain.filter(exchange);
    }

    private boolean isValidUUID(String str) {
        try {
            java.util.UUID.fromString(str);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
