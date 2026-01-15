package com.simiyami.orderapi.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
@Import(TestSecurityConfig.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Health 엔드포인트는 인증이 필요함 - 인증 없이 호출 시 401")
    void healthEndpointShouldRequireAuth() throws Exception {
        mockMvc.perform(get("/api/orders/health"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Health 엔드포인트 - JWT 인증 시 정상 응답")
    void healthEndpointShouldReturnOkWithAuth() throws Exception {
        Jwt jwt = Jwt.withTokenValue("mock-token")
            .header("alg", "RS256")
            .subject("user-123")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();

        mockMvc.perform(get("/api/orders/health")
                .with(jwt().jwt(jwt)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.service").value("order-api"));
    }

    @Test
    @DisplayName("인증되지 않은 사용자는 /me 엔드포인트 접근 불가")
    void meEndpointShouldRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/orders/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("JWT 인증된 사용자는 /me 엔드포인트 접근 가능")
    void meEndpointShouldBeAccessibleWithJwt() throws Exception {
        Jwt jwt = Jwt.withTokenValue("mock-token")
            .header("alg", "RS256")
            .subject("user-123")
            .claim("preferred_username", "testuser")
            .claim("email", "testuser@example.com")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();

        mockMvc.perform(get("/api/orders/me")
                .with(jwt().jwt(jwt).authorities(List.of(new SimpleGrantedAuthority("ROLE_USER"))))
                .header("X-Idempotency-Key", "550e8400-e29b-41d4-a716-446655440000")
                .header("X-Trace-Id", "trace-123"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value("user-123"))
            .andExpect(jsonPath("$.username").value("testuser"))
            .andExpect(jsonPath("$.email").value("testuser@example.com"))
            .andExpect(jsonPath("$.idempotencyKey").value("550e8400-e29b-41d4-a716-446655440000"))
            .andExpect(jsonPath("$.traceId").value("trace-123"));
    }

    @Test
    @DisplayName("X-Idempotency-Key 헤더 없이도 GET 요청은 성공")
    void meEndpointShouldWorkWithoutIdempotencyKey() throws Exception {
        Jwt jwt = Jwt.withTokenValue("mock-token")
            .header("alg", "RS256")
            .subject("user-123")
            .claim("preferred_username", "testuser")
            .claim("email", "testuser@example.com")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();

        mockMvc.perform(get("/api/orders/me")
                .with(jwt().jwt(jwt)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.idempotencyKey").value("not provided"));
    }
}
