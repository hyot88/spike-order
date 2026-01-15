package com.simiyami.gateway.filter;

import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER);

        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        final String finalTraceId = traceId;

        ServerHttpRequest request = exchange.getRequest().mutate()
            .header(TRACE_ID_HEADER, traceId)
            .build();

        ServerHttpResponse originalResponse = exchange.getResponse();
        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            /**
             * 응답 본문(Body)이 쓰이기 직전에 Trace ID 헤더를 주입합니다.
             * * [Note]
             * HTTP 프로토콜상 Body가 전송되기 시작(Committed)하면 헤더를 수정할 수 없습니다.
             * 따라서 데이터를 쓰는 시점인 writeWith, writeAndFlushWith를 가로채서
             * 실제 쓰기 작업(super)이 발생하기 전에 헤더를 추가해야 합니다.
             */

            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                getHeaders().add(TRACE_ID_HEADER, finalTraceId);
                return super.writeWith(body);
            }

            // 스트리밍 등 Flush가 동반되는 전송 방식에서도 헤더 누락을 방지하기 위해 함께 재정의
            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                getHeaders().add(TRACE_ID_HEADER, finalTraceId);
                return super.writeAndFlushWith(body);
            }
        };

        ServerWebExchange mutatedExchange = exchange.mutate()
            .request(request)
            .response(decoratedResponse)
            .build();

        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
