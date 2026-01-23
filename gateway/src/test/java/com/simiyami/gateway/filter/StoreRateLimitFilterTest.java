package com.simiyami.gateway.filter;

import com.simiyami.gateway.config.StoreRateLimitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StoreRateLimitFilterTest {

    private StoreRateLimitFilter filter;
    private StoreRateLimitConfig config;
    private GatewayFilterChain filterChain;

    @BeforeEach
    void setUp() {
        config = new StoreRateLimitConfig();
        filter = new StoreRateLimitFilter(config);
        filterChain = mock(GatewayFilterChain.class);
        when(filterChain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("X-Store-Id 헤더가 있으면 가게별 Rate Limit 적용")
    void shouldApplyStoreRateLimitWithHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders")
            .header("X-Store-Id", "store-123")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
            .verifyComplete();

        // 요청이 통과했으면 status가 null (filterChain이 처리)
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("X-Store-Id 헤더가 없으면 가게별 Rate Limit 건너뜀")
    void shouldSkipStoreRateLimitWhenNoStoreIdHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("X-Store-Id 헤더가 빈 문자열이면 Rate Limit 건너뜀")
    void shouldSkipStoreRateLimitWhenEmptyStoreIdHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders")
            .header("X-Store-Id", "")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("X-Store-Id 헤더가 공백만 있으면 Rate Limit 건너뜀")
    void shouldSkipStoreRateLimitWhenBlankStoreIdHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders")
            .header("X-Store-Id", "   ")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("가게별 Rate Limit 초과 시 429 응답")
    void shouldReturn429WhenStoreRateLimitExceeded() {
        // 테스트를 위해 낮은 limit 설정
        config.setLimit("store-test", 5);

        HttpStatus lastStatus = null;

        for (int i = 0; i < 10; i++) {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders")
                .header("X-Store-Id", "store-test")
                .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

            if (exchange.getResponse().getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                lastStatus = (HttpStatus) exchange.getResponse().getStatusCode();
                assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Type"))
                    .isEqualTo("store");
                assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Store-Id"))
                    .isEqualTo("store-test");
                assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Retry-After"))
                    .isEqualTo("60");
                break;
            }
        }

        assertThat(lastStatus).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("서로 다른 가게는 별도 Rate Limit 적용")
    void shouldApplySeparateRateLimitPerStore() {
        config.setLimit("store-A", 3);
        config.setLimit("store-B", 3);

        // store-A의 limit 소진
        for (int i = 0; i < 4; i++) {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders")
                .header("X-Store-Id", "store-A")
                .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();
        }

        // store-B는 여전히 사용 가능해야 함
        MockServerHttpRequest requestB = MockServerHttpRequest.get("/api/orders")
            .header("X-Store-Id", "store-B")
            .build();
        MockServerWebExchange exchangeB = MockServerWebExchange.from(requestB);

        StepVerifier.create(filter.filter(exchangeB, filterChain))
            .verifyComplete();

        assertThat(exchangeB.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("필터 순서는 1 (RateLimitFilter 이후)")
    void shouldHaveOrderAfterRateLimitFilter() {
        assertThat(filter.getOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("기본 Rate Limit은 5000")
    void shouldUseDefaultLimitOf5000() {
        // 기본 limit 확인
        assertThat(config.getLimit("any-store")).isEqualTo(5000);

        // 많은 요청을 보내도 5000 이하면 통과
        for (int i = 0; i < 100; i++) {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders")
                .header("X-Store-Id", "store-default")
                .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    @Test
    @DisplayName("동적으로 Rate Limit 변경 시 새 버킷 생성")
    void shouldRecreateBucketWhenLimitChanges() {
        config.setLimit("store-dynamic", 3);

        // 2개 토큰 사용
        for (int i = 0; i < 2; i++) {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders")
                .header("X-Store-Id", "store-dynamic")
                .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();
        }

        // limit을 높게 변경 (새 버킷 생성됨)
        config.setLimit("store-dynamic", 100);

        // 변경된 limit으로 새 버킷이 생성되어야 함
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders")
            .header("X-Store-Id", "store-dynamic")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
