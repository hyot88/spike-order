package com.simiyami.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TraceIdFilterTest {

    private TraceIdFilter traceIdFilter;
    private GatewayFilterChain filterChain;
    private ArgumentCaptor<ServerWebExchange> exchangeCaptor;

    @BeforeEach
    void setUp() {
        traceIdFilter = new TraceIdFilter();
        filterChain = mock(GatewayFilterChain.class);
        exchangeCaptor = ArgumentCaptor.forClass(ServerWebExchange.class);
        when(filterChain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Trace ID가 없으면 새로 생성하여 요청 헤더에 추가한다")
    void shouldGenerateTraceIdWhenNotPresent() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders/health").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(traceIdFilter.filter(exchange, filterChain))
            .verifyComplete();

        verify(filterChain).filter(exchangeCaptor.capture());
        ServerWebExchange capturedExchange = exchangeCaptor.getValue();

        // 요청 헤더에 Trace ID가 추가되었는지 확인
        String traceId = capturedExchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        assertThat(traceId).isNotNull();
        assertThat(traceId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("클라이언트가 보낸 Trace ID를 요청 헤더에 유지한다")
    void shouldPreserveExistingTraceId() {
        String existingTraceId = "my-custom-trace-id-123";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders/health")
            .header("X-Trace-Id", existingTraceId)
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(traceIdFilter.filter(exchange, filterChain))
            .verifyComplete();

        verify(filterChain).filter(exchangeCaptor.capture());
        ServerWebExchange capturedExchange = exchangeCaptor.getValue();

        // 요청 헤더에 기존 Trace ID가 유지되었는지 확인
        String traceId = capturedExchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        assertThat(traceId).isEqualTo(existingTraceId);
    }

    @Test
    @DisplayName("응답 시 Trace ID가 응답 헤더에 추가된다")
    void shouldAddTraceIdToResponseHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders/health").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // filterChain이 실제로 응답을 쓰도록 설정
        when(filterChain.filter(any())).thenAnswer(invocation -> {
            ServerWebExchange ex = invocation.getArgument(0);
            DataBuffer buffer = new DefaultDataBufferFactory().wrap("test".getBytes(StandardCharsets.UTF_8));
            return ex.getResponse().writeWith(Mono.just(buffer));
        });

        StepVerifier.create(traceIdFilter.filter(exchange, filterChain))
            .verifyComplete();

        verify(filterChain).filter(exchangeCaptor.capture());
        ServerWebExchange capturedExchange = exchangeCaptor.getValue();

        // 응답 헤더에 Trace ID가 추가되었는지 확인
        String traceId = capturedExchange.getResponse().getHeaders().getFirst("X-Trace-Id");
        assertThat(traceId).isNotNull();
        assertThat(traceId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
