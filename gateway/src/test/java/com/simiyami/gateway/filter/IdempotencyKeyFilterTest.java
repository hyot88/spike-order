package com.simiyami.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdempotencyKeyFilterTest {

    private IdempotencyKeyFilter idempotencyKeyFilter;
    private GatewayFilterChain filterChain;

    @BeforeEach
    void setUp() {
        idempotencyKeyFilter = new IdempotencyKeyFilter();
        filterChain = mock(GatewayFilterChain.class);
        when(filterChain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("GET 요청은 멱등키 없이도 통과한다")
    void shouldPassGetRequestWithoutIdempotencyKey() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(idempotencyKeyFilter.filter(exchange, filterChain))
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull(); // 필터에서 상태 설정 안 함
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH"})
    @DisplayName("POST/PUT/PATCH 요청은 유효한 UUID 멱등키가 필요하다")
    void shouldRequireIdempotencyKeyForMutatingRequests(String method) {
        String validUuid = UUID.randomUUID().toString();
        MockServerHttpRequest request = MockServerHttpRequest.method(HttpMethod.valueOf(method), "/api/orders")
            .header("X-Idempotency-Key", validUuid)
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(idempotencyKeyFilter.filter(exchange, filterChain))
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull(); // 통과
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH"})
    @DisplayName("POST/PUT/PATCH 요청에서 멱등키가 없으면 400 에러")
    void shouldRejectMutatingRequestsWithoutIdempotencyKey(String method) {
        MockServerHttpRequest request = MockServerHttpRequest.method(HttpMethod.valueOf(method), "/api/orders")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(idempotencyKeyFilter.filter(exchange, filterChain))
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("잘못된 형식의 멱등키는 400 에러")
    void shouldRejectInvalidUuidFormat() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/orders")
            .header("X-Idempotency-Key", "not-a-valid-uuid")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(idempotencyKeyFilter.filter(exchange, filterChain))
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("빈 멱등키는 400 에러")
    void shouldRejectEmptyIdempotencyKey() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/orders")
            .header("X-Idempotency-Key", "")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(idempotencyKeyFilter.filter(exchange, filterChain))
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
