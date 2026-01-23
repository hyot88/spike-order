package com.simiyami.gateway.integration;

import org.junit.jupiter.api.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Eureka Service Discovery 통합 테스트
 *
 * 사전 조건:
 * - docker-compose up -d (KeyCloak)
 * - ./gradlew :eureka-server:bootRun (Eureka Server on 8761)
 * - ./gradlew :gateway:bootRun (Gateway on 8081)
 * - ./gradlew :order-api:bootRun (Order API on 8082)
 *
 * 실행:
 * ./gradlew :gateway:test --tests "*ServiceDiscoveryIntegrationTest" -Dintegration.test.enabled=true
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceDiscoveryIntegrationTest {

    private static final String KEYCLOAK_URL = "http://localhost:8080";
    private static final String EUREKA_URL = "http://localhost:8761";
    private static final String GATEWAY_URL = "http://localhost:8081";
    private static final String CLIENT_ID = "spike-order-client";

    private WebClient keycloakClient;
    private WebClient eurekaClient;
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

        eurekaClient = WebClient.builder()
            .baseUrl(EUREKA_URL)
            .build();

        gatewayClient = WebClient.builder()
            .baseUrl(GATEWAY_URL)
            .build();

        // Eureka Server 연결 확인
        try {
            eurekaClient.get()
                .uri("/actuator/health")
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(5));
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "Eureka Server에 연결할 수 없습니다: " + e.getMessage());
        }

        // Access Token 획득
        try {
            Map<String, Object> tokenResponse = keycloakClient.post()
                .uri("/realms/spike-order/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "password")
                    .with("client_id", CLIENT_ID)
                    .with("username", "testuser")
                    .with("password", "testpass"))
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(10));

            accessToken = (String) tokenResponse.get("access_token");
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "KeyCloak에 연결할 수 없습니다: " + e.getMessage());
        }
    }

    @Test
    @Order(1)
    @DisplayName("④ Eureka Server Health Check")
    void eurekaServerShouldBeHealthy() {
        Map<String, Object> response = eurekaClient.get()
            .uri("/actuator/health")
            .retrieve()
            .bodyToMono(Map.class)
            .block(Duration.ofSeconds(10));

        assertThat(response).isNotNull();
        assertThat(response.get("status")).isEqualTo("UP");
    }

    @Test
    @Order(2)
    @DisplayName("⑤ API-GATEWAY가 Eureka에 등록됨")
    void apiGatewayShouldBeRegisteredInEureka() {
        // 최대 30초 대기
        boolean registered = false;
        for (int i = 0; i < 6; i++) {
            try {
                Map<String, Object> response = eurekaClient.get()
                    .uri("/eureka/apps/API-GATEWAY")
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(10));

                if (response != null && response.containsKey("application")) {
                    registered = true;
                    break;
                }
            } catch (Exception e) {
                // 아직 등록되지 않음
            }

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        assertThat(registered).as("API-GATEWAY가 Eureka에 등록되어야 합니다").isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("⑤ ORDER-API가 Eureka에 등록됨")
    void orderApiShouldBeRegisteredInEureka() {
        // 최대 30초 대기
        boolean registered = false;
        for (int i = 0; i < 6; i++) {
            try {
                Map<String, Object> response = eurekaClient.get()
                    .uri("/eureka/apps/ORDER-API")
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(10));

                if (response != null && response.containsKey("application")) {
                    registered = true;
                    break;
                }
            } catch (Exception e) {
                // 아직 등록되지 않음
            }

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        assertThat(registered).as("ORDER-API가 Eureka에 등록되어야 합니다").isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("⑥ Gateway가 Eureka를 통해 ORDER-API로 라우팅")
    void gatewayShouldRouteToOrderApiThroughEureka() {
        AtomicReference<HttpStatus> statusRef = new AtomicReference<>();

        Map<String, Object> response = gatewayClient.get()
            .uri("/api/orders/health")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .exchangeToMono(clientResponse -> {
                statusRef.set((HttpStatus) clientResponse.statusCode());
                return clientResponse.bodyToMono(Map.class);
            })
            .block(Duration.ofSeconds(10));

        assertThat(statusRef.get()).isEqualTo(HttpStatus.OK);
        assertThat(response.get("status")).isEqualTo("UP");
        assertThat(response.get("service")).isEqualTo("order-api");
    }

    @Test
    @Order(5)
    @DisplayName("⑥ Gateway LoadBalancer - 여러 요청이 정상 처리됨")
    void gatewayLoadBalancerShouldHandleMultipleRequests() {
        int successCount = 0;
        int totalRequests = 10;

        for (int i = 0; i < totalRequests; i++) {
            try {
                Map<String, Object> response = gatewayClient.get()
                    .uri("/api/orders/health")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(5));

                if ("UP".equals(response.get("status"))) {
                    successCount++;
                }
            } catch (Exception e) {
                // 요청 실패
            }
        }

        assertThat(successCount).isEqualTo(totalRequests);
    }

    @Test
    @Order(6)
    @DisplayName("④ 가게별 Rate Limit - X-Store-Id 헤더로 요청")
    void storeRateLimitShouldWorkWithStoreIdHeader() {
        AtomicReference<HttpStatus> statusRef = new AtomicReference<>();

        gatewayClient.get()
            .uri("/api/orders/health")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .header("X-Store-Id", "store-integration-test")
            .exchangeToMono(response -> {
                statusRef.set((HttpStatus) response.statusCode());
                return response.bodyToMono(Map.class);
            })
            .block(Duration.ofSeconds(10));

        assertThat(statusRef.get()).isEqualTo(HttpStatus.OK);
    }
}
