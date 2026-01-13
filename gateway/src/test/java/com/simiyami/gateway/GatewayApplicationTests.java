package com.simiyami.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.com",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://example.com/.well-known/jwks.json"
})
class GatewayApplicationTests {

    @Test
    void contextLoads() {
    }
}
