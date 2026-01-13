package com.simiyami.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private RateLimitFilter rateLimitFilter;
    private GatewayFilterChain filterChain;

    @BeforeEach
    void setUp() {
        rateLimitFilter = new RateLimitFilter();
        filterChain = mock(GatewayFilterChain.class);
        when(filterChain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Rate Limit 내에서는 요청이 통과한다")
    void shouldPassRequestsWithinRateLimit() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders")
            .header("X-Forwarded-For", "192.168.1.100")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(rateLimitFilter.filter(exchange, filterChain))
            .verifyComplete();

        // filterChain.filter가 호출되었으므로 상태 코드가 설정되지 않음 (통과)
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("IP별 Rate Limit 초과 시 429 응답")
    void shouldReturnTooManyRequestsWhenRateLimitExceeded() {
        String clientIp = "10.0.0.1";

        // IP별 Rate Limit은 1000 req/min
        // 1001번째 요청에서 429 응답 예상
        HttpStatusCode lastStatus = null;

        for (int i = 0; i < 1005; i++) {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders")
                .header("X-Forwarded-For", clientIp)
                .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(rateLimitFilter.filter(exchange, filterChain))
                .verifyComplete();

            if (exchange.getResponse().getStatusCode() != null) {
                lastStatus = exchange.getResponse().getStatusCode();
                if (lastStatus.value() == 429) {
                    break;
                }
            }
        }

        assertThat(lastStatus).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("서로 다른 IP는 별도로 Rate Limit이 적용된다")
    void shouldApplySeparateRateLimitPerIp() {
        MockServerHttpRequest request1 = MockServerHttpRequest.get("/api/orders")
            .header("X-Forwarded-For", "192.168.1.1")
            .build();
        MockServerWebExchange exchange1 = MockServerWebExchange.from(request1);

        MockServerHttpRequest request2 = MockServerHttpRequest.get("/api/orders")
            .header("X-Forwarded-For", "192.168.1.2")
            .build();
        MockServerWebExchange exchange2 = MockServerWebExchange.from(request2);

        StepVerifier.create(rateLimitFilter.filter(exchange1, filterChain))
            .verifyComplete();
        StepVerifier.create(rateLimitFilter.filter(exchange2, filterChain))
            .verifyComplete();

        // 둘 다 통과해야 함
        assertThat(exchange1.getResponse().getStatusCode()).isNull();
        assertThat(exchange2.getResponse().getStatusCode()).isNull();
    }
}
