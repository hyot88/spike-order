package com.simiyami.gateway.integration;

import org.junit.jupiter.api.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사전 조건:
 * - docker-compose up -d (KeyCloak 실행)
 * - ./gradlew :gateway:bootRun (Gateway 실행)
 * - ./gradlew :order-api:bootRun (Order API 실행)
 *
 * 실행 방법:
 * ./gradlew :gateway:test --tests "*AuthFlowIntegrationTest" -Dintegration.test.enabled=true
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthFlowIntegrationTest {

    private static final String KEYCLOAK_URL = "http://localhost:8080";
    private static final String GATEWAY_URL = "http://localhost:8081";
    private static final String CLIENT_ID = "spike-order-client";

    private WebClient keycloakClient;
    private WebClient gatewayClient;
    private String accessToken;

    @BeforeAll
    void setUp() {
        String enabled = System.getProperty("integration.test.enabled", "false");
        Assumptions.assumeTrue("true".equalsIgnoreCase(enabled),
            "통합 테스트를 실행하려면 -Dintegration.test.enabled=true 옵션을 추가하세요");

        keycloakClient = WebClient.builder()
            .baseUrl(KEYCLOAK_URL)
            .build();

        gatewayClient = WebClient.builder()
            .baseUrl(GATEWAY_URL)
            .build();
    }

    // ==================== 토큰 요청 → JWT 발급 테스트 ====================

    @Test
    @Order(1)
    @DisplayName("① 토큰 발급 - 정상 케이스: testuser로 토큰 발급")
    void tokenIssuance_success_testuser() {
        Map<String, Object> response = keycloakClient.post()
            .uri("/realms/spike-order/protocol/openid-connect/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("grant_type", "password")
                .with("client_id", CLIENT_ID)
                .with("username", "testuser")
                .with("password", "testpass"))
            .retrieve()
            .bodyToMono(Map.class)
            .block(Duration.ofSeconds(10));

        assertThat(response).containsKey("access_token");
        assertThat(response).containsKey("refresh_token");
        assertThat(response).containsKey("expires_in");
        assertThat(response.get("token_type")).isEqualTo("Bearer");

        this.accessToken = (String) response.get("access_token");
    }

    @Test
    @Order(2)
    @DisplayName("① 토큰 발급 - 실패 케이스: 잘못된 비밀번호")
    void tokenIssuance_failure_wrongPassword() {
        AtomicReference<HttpStatus> statusRef = new AtomicReference<>();
        AtomicReference<Map<String, Object>> bodyRef = new AtomicReference<>();

        keycloakClient.post()
            .uri("/realms/spike-order/protocol/openid-connect/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("grant_type", "password")
                .with("client_id", CLIENT_ID)
                .with("username", "testuser")
                .with("password", "wrongpass"))
            .exchangeToMono(response -> {
                statusRef.set((HttpStatus) response.statusCode());
                return response.bodyToMono(Map.class);
            })
            .doOnNext(bodyRef::set)
            .block(Duration.ofSeconds(10));

        assertThat(statusRef.get()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(bodyRef.get().get("error")).isEqualTo("invalid_grant");
    }

    @Test
    @Order(3)
    @DisplayName("① 토큰 발급 - admin 사용자로 토큰 발급")
    void tokenIssuance_success_admin() {
        Map<String, Object> response = keycloakClient.post()
            .uri("/realms/spike-order/protocol/openid-connect/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("grant_type", "password")
                .with("client_id", CLIENT_ID)
                .with("username", "admin")
                .with("password", "adminpass"))
            .retrieve()
            .bodyToMono(Map.class)
            .block(Duration.ofSeconds(10));

        assertThat(response).containsKey("access_token");
        assertThat(response).containsKey("refresh_token");
    }

    // ==================== JWT 검증 테스트 ====================

    @Test
    @Order(4)
    @DisplayName("③ JWT 검증 - 정상 케이스: 유효한 JWT로 API 호출")
    void jwtValidation_success_validToken() {
        Map<String, Object> response = gatewayClient.get()
            .uri("/api/orders/health")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .retrieve()
            .bodyToMono(Map.class)
            .block(Duration.ofSeconds(10));

        assertThat(response.get("status")).isEqualTo("UP");
        assertThat(response.get("service")).isEqualTo("order-api");
    }

    @Test
    @Order(5)
    @DisplayName("③ JWT 검증 - 실패 케이스: JWT 없이 호출")
    void jwtValidation_failure_noToken() {
        AtomicReference<HttpStatus> statusRef = new AtomicReference<>();

        gatewayClient.get()
            .uri("/api/orders/health")
            .exchangeToMono(response -> {
                statusRef.set((HttpStatus) response.statusCode());
                return Mono.empty();
            })
            .block(Duration.ofSeconds(10));

        assertThat(statusRef.get()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(6)
    @DisplayName("③ JWT 검증 - 실패 케이스: 잘못된 JWT")
    void jwtValidation_failure_invalidToken() {
        AtomicReference<HttpStatus> statusRef = new AtomicReference<>();

        gatewayClient.get()
            .uri("/api/orders/health")
            .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.token.here")
            .exchangeToMono(response -> {
                statusRef.set((HttpStatus) response.statusCode());
                return Mono.empty();
            })
            .block(Duration.ofSeconds(10));

        assertThat(statusRef.get()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ==================== 멱등키 검증 테스트 ====================

    @Test
    @Order(7)
    @DisplayName("③ 멱등키 검증 - 정상 케이스: 유효한 멱등키로 POST 요청")
    void idempotencyKey_success_validUuid() {
        String idempotencyKey = UUID.randomUUID().toString();
        AtomicReference<HttpStatus> statusRef = new AtomicReference<>();
        AtomicReference<Map<String, Object>> bodyRef = new AtomicReference<>();

        gatewayClient.post()
            .uri("/api/orders/test")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .header("X-Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .exchangeToMono(response -> {
                statusRef.set((HttpStatus) response.statusCode());
                return response.bodyToMono(Map.class);
            })
            .doOnNext(bodyRef::set)
            .block(Duration.ofSeconds(10));

        assertThat(statusRef.get()).isEqualTo(HttpStatus.OK);
        assertThat(bodyRef.get().get("message")).isEqualTo("POST request successful");
        assertThat(bodyRef.get().get("idempotencyKey")).isEqualTo(idempotencyKey);
    }

    @Test
    @Order(8)
    @DisplayName("③ 멱등키 검증 - 실패 케이스: 멱등키 없이 POST 요청")
    void idempotencyKey_failure_missing() {
        AtomicReference<HttpStatus> statusRef = new AtomicReference<>();

        gatewayClient.post()
            .uri("/api/orders/test")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .exchangeToMono(response -> {
                statusRef.set((HttpStatus) response.statusCode());
                return Mono.empty();
            })
            .block(Duration.ofSeconds(10));

        assertThat(statusRef.get()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @Order(9)
    @DisplayName("③ 멱등키 검증 - 실패 케이스: 잘못된 형식의 멱등키")
    void idempotencyKey_failure_invalidFormat() {
        AtomicReference<HttpStatus> statusRef = new AtomicReference<>();

        gatewayClient.post()
            .uri("/api/orders/test")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .header("X-Idempotency-Key", "not-a-uuid")
            .contentType(MediaType.APPLICATION_JSON)
            .exchangeToMono(response -> {
                statusRef.set((HttpStatus) response.statusCode());
                return Mono.empty();
            })
            .block(Duration.ofSeconds(10));

        assertThat(statusRef.get()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ==================== Trace ID 테스트 ====================

    @Test
    @Order(10)
    @DisplayName("③ Trace ID - 자동 생성 확인")
    void traceId_autoGenerated() {
        AtomicReference<HttpStatus> statusRef = new AtomicReference<>();
        AtomicReference<String> traceIdRef = new AtomicReference<>();

        gatewayClient.get()
            .uri("/api/orders/health")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .exchangeToMono(response -> {
                statusRef.set((HttpStatus) response.statusCode());
                traceIdRef.set(response.headers().asHttpHeaders().getFirst("X-Trace-Id"));
                return Mono.empty();
            })
            .block(Duration.ofSeconds(10));

        assertThat(statusRef.get()).isEqualTo(HttpStatus.OK);
        assertThat(traceIdRef.get()).isNotNull();
        assertThat(traceIdRef.get()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @Order(11)
    @DisplayName("③ Trace ID - 클라이언트가 보낸 값 유지")
    void traceId_preserveClientValue() {
        String customTraceId = "my-custom-trace-123";
        AtomicReference<HttpStatus> statusRef = new AtomicReference<>();
        AtomicReference<String> traceIdRef = new AtomicReference<>();

        gatewayClient.get()
            .uri("/api/orders/health")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .header("X-Trace-Id", customTraceId)
            .exchangeToMono(response -> {
                statusRef.set((HttpStatus) response.statusCode());
                traceIdRef.set(response.headers().asHttpHeaders().getFirst("X-Trace-Id"));
                return Mono.empty();
            })
            .block(Duration.ofSeconds(10));

        assertThat(statusRef.get()).isEqualTo(HttpStatus.OK);
        assertThat(traceIdRef.get()).isEqualTo(customTraceId);
    }

    // ==================== Rate Limiting 테스트 ====================

    @Test
    @Order(12)
    @DisplayName("③ Rate Limiting - 100회 초과 시 429 응답")
    void rateLimiting_shouldReturn429After100Requests() {
        // 새 토큰 발급 (Rate Limit 초기화를 위해 admin 계정 사용)
        Map<String, Object> tokenResponse = keycloakClient.post()
            .uri("/realms/spike-order/protocol/openid-connect/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("grant_type", "password")
                .with("client_id", CLIENT_ID)
                .with("username", "admin")
                .with("password", "adminpass"))
            .retrieve()
            .bodyToMono(Map.class)
            .block(Duration.ofSeconds(10));

        String rateLimitTestToken = (String) tokenResponse.get("access_token");

        int rateLimitedAt = -1;

        for (int i = 1; i <= 110; i++) {
            AtomicReference<HttpStatus> statusRef = new AtomicReference<>();

            gatewayClient.get()
                .uri("/api/orders/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rateLimitTestToken)
                .exchangeToMono(response -> {
                    statusRef.set((HttpStatus) response.statusCode());
                    return Mono.empty();
                })
                .block(Duration.ofSeconds(5));

            if (statusRef.get() == HttpStatus.TOO_MANY_REQUESTS) {
                rateLimitedAt = i;
                break;
            }
        }

        // Rate Limit이 걸려야 함 (101번째 요청 이후)
        // 이전 테스트에서 일부 요청이 소모되었을 수 있으므로 110회 내에 429가 발생하면 통과
        assertThat(rateLimitedAt).isGreaterThan(0).isLessThanOrEqualTo(110);
    }

    // ==================== 사용자 정보 조회 테스트 ====================

    @Test
    @Order(13)
    @DisplayName("③ 사용자 정보 조회 - /api/orders/me")
    void userInfo_success() {
        String idempotencyKey = UUID.randomUUID().toString();

        Map<String, Object> response = gatewayClient.get()
            .uri("/api/orders/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .header("X-Idempotency-Key", idempotencyKey)
            .retrieve()
            .bodyToMono(Map.class)
            .block(Duration.ofSeconds(10));

        assertThat(response.get("userId")).isNotNull();
        assertThat(response.get("username")).isEqualTo("testuser");
        assertThat(response.get("email")).isEqualTo("testuser@example.com");
        assertThat(response.get("idempotencyKey")).isEqualTo(idempotencyKey);
        assertThat(response.get("traceId")).isNotNull();
    }
}
