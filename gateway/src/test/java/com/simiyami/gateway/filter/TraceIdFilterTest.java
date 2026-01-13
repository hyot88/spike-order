package com.simiyami.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceIdFilterTest {

    private TraceIdFilter traceIdFilter;
    private GatewayFilterChain filterChain;

    @BeforeEach
    void setUp() {
        traceIdFilter = new TraceIdFilter();
        filterChain = mock(GatewayFilterChain.class);
        when(filterChain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Trace ID가 없으면 새로 생성한다")
    void shouldGenerateTraceIdWhenNotPresent() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders/health").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(traceIdFilter.filter(exchange, filterChain))
            .verifyComplete();

        String traceId = exchange.getResponse().getHeaders().getFirst("X-Trace-Id");
        assertThat(traceId).isNotNull();
        assertThat(traceId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("클라이언트가 보낸 Trace ID를 유지한다")
    void shouldPreserveExistingTraceId() {
        String existingTraceId = "my-custom-trace-id-123";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders/health")
            .header("X-Trace-Id", existingTraceId)
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(traceIdFilter.filter(exchange, filterChain))
            .verifyComplete();

        String traceId = exchange.getResponse().getHeaders().getFirst("X-Trace-Id");
        assertThat(traceId).isEqualTo(existingTraceId);
    }
}
