# Flow 1-3: 인증 플로우 가이드

> 이 문서는 인증 플로우(①~③)의 구현을 설명합니다.
> 코드를 처음 보거나 오랜만에 다시 볼 때, 이 문서를 따라가면 전체 흐름을 이해할 수 있습니다.

---

## 목차

1. [개요](#1-개요)
2. [전체 플로우](#2-전체-플로우)
3. [① 토큰 요청](#3--토큰-요청)
4. [② JWT 발급](#4--jwt-발급)
5. [③ API 요청 처리](#5--api-요청-처리)
6. [필터 실행 순서](#6-필터-실행-순서)
7. [파일 구조 요약](#7-파일-구조-요약)
8. [테스트 방법](#8-테스트-방법)
9. [트러블슈팅](#9-트러블슈팅)

---

## 1. 개요

### 이 플로우가 하는 일

클라이언트가 API를 호출하기 위해 **인증 토큰을 발급받고**, 그 토큰으로 **API Gateway를 통해 요청**하는 과정입니다.

### 핵심 설계 원칙

| 원칙 | 설명 | 구현 |
|------|------|------|
| **토큰 기반 인증** | 매 요청마다 인증 서버를 거치지 않음 | JWT를 Gateway에서 직접 검증 |
| **중앙 집중식 인증** | 인증 로직을 한 곳에서 관리 | KeyCloak에서 사용자/토큰 관리 |
| **횡단 관심사 분리** | 인증, Rate Limit 등을 비즈니스 로직과 분리 | Gateway Filter로 처리 |

### 왜 이렇게 설계했나?

```
❌ 안티패턴: 매 요청마다 인증 서버 호출
[Client] → [API Gateway] → [KeyCloak에서 검증] → [Backend]
                              ↑ 병목 지점

✅ 채택한 패턴: JWT 자체 검증
[Client] → [API Gateway에서 JWT 서명 검증] → [Backend]
              ↑ 공개키로 서명만 확인하면 됨
```

- KeyCloak은 **토큰 발급 시점에만** 호출됩니다
- Gateway는 **공개키(JWK)**를 캐싱하여 JWT 서명을 직접 검증합니다
- 이로써 인증 서버가 단일 장애점(SPOF)이 되는 것을 방지합니다

---

## 2. 전체 플로우

```
┌──────────┐      ① 토큰 요청        ┌──────────┐
│          │ ─────────────────────→ │          │
│  Client  │                        │ KeyCloak │
│          │ ←───────────────────── │          │
└──────────┘      ② JWT 발급         └──────────┘
     │
     │ ③ API 요청 (JWT + 멱등키)
     ▼
┌────────────────────────────────────────────────────────┐
│                     API Gateway                         │
│  ┌─────────────┐ ┌─────────────┐ ┌──────────────────┐  │
│  │ TraceIdFilter│→│IdempotencyKey│→│ SecurityFilter │  │
│  │  (최우선)    │ │   Filter     │ │  (JWT 검증)     │  │
│  └─────────────┘ └─────────────┘ └──────────────────┘  │
│                                           │             │
│                          ┌────────────────┘             │
│                          ▼                              │
│                  ┌─────────────┐                        │
│                  │RateLimitFilter│                      │
│                  │(인증 후 실행) │                       │
│                  └─────────────┘                        │
└────────────────────────────────────────────────────────┘
     │
     │ 라우팅
     ▼
┌──────────┐
│ Order API │
└──────────┘
```

---

## 3. ① 토큰 요청

### 관련 파일

```
keycloak/
└── realm-export.json    # KeyCloak Realm 설정
```

### 요청 방법

```bash
curl -X POST http://localhost:8080/realms/spike-order/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=spike-order-client" \
  -d "username=testuser" \
  -d "password=testpass"
```

### 처리 원리

1. 클라이언트가 **사용자 자격증명**(username/password)을 KeyCloak에 전송
2. KeyCloak이 자격증명을 검증
3. 유효하면 JWT Access Token과 Refresh Token 발급

### realm-export.json 핵심 설정

```json
{
  "realm": "spike-order",
  "clients": [{
    "clientId": "spike-order-client",
    "publicClient": true,           // 클라이언트 시크릿 불필요
    "directAccessGrantsEnabled": true  // Resource Owner Password Grant 허용
  }],
  "users": [{
    "username": "testuser",
    "credentials": [{
      "type": "password",
      "value": "testpass"
    }]
  }]
}
```

| 설정 | 설명 | 왜 이렇게? |
|------|------|----------|
| `publicClient: true` | 클라이언트 시크릿 불필요 | 모바일/SPA에서 시크릿 보관이 어려움 |
| `directAccessGrantsEnabled` | 직접 ID/PW로 토큰 발급 | 테스트 및 서버 간 통신에 유용 |

---

## 4. ② JWT 발급

### JWT 구조

```
eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.  ← Header (알고리즘 정보)
eyJzdWIiOiJ1c2VyLTEyMyIsInByZWZlcnJlZF91c2VybmFtZSI6InRlc3R1c2VyIiwiZW1haWwiOiJ0ZXN0dXNlckBleGFtcGxlLmNvbSJ9.  ← Payload (클레임)
SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c  ← Signature (서명)
```

### Payload(클레임) 예시

```json
{
  "exp": 1705312800,                    // 만료 시간
  "iat": 1705309200,                    // 발급 시간
  "jti": "abc-123-def",                 // 토큰 고유 ID
  "iss": "http://localhost:8080/realms/spike-order",  // 발급자
  "sub": "user-uuid-123",               // 사용자 ID (없을 수 있음)
  "preferred_username": "testuser",     // 사용자명
  "email": "testuser@example.com"       // 이메일
}
```

### 서명 검증 원리

```
1. KeyCloak이 비밀키(Private Key)로 JWT 서명
2. Gateway가 공개키(Public Key)로 서명 검증
3. 서명이 유효하면 → 이 토큰은 KeyCloak이 발급한 것이 맞음

[KeyCloak]                        [Gateway]
    │                                 │
    │ 비밀키로 서명                    │
    │ ──────────────────────────────→ │
    │          JWT                    │ 공개키로 검증
    │                                 │
```

**왜 이 방식인가?**
- 비밀키는 KeyCloak만 보유 → 토큰 위조 불가능
- 공개키는 누구나 가져갈 수 있음 → Gateway가 독립적으로 검증 가능
- KeyCloak 장애 시에도 이미 발급된 토큰은 계속 사용 가능

---

## 5. ③ API 요청 처리

### 5.1 Gateway Security 설정

**파일 위치:** `gateway/src/main/java/com/simiyami/gateway/config/SecurityConfig.java`

```java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)  // ①
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/health").permitAll()  // ②
                .anyExchange().authenticated())  // ③
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> {}));  // ④

        return http.build();
    }
}
```

| 번호 | 설정 | 설명 | 왜 이렇게? |
|-----|------|------|----------|
| ① | CSRF 비활성화 | CSRF 토큰 검증 안 함 | JWT 자체가 CSRF 방어 역할, Stateless API라 세션 없음 |
| ② | /actuator/health 허용 | 헬스체크 엔드포인트 | K8s/로드밸런서의 헬스체크용 |
| ③ | 나머지는 인증 필요 | JWT 없으면 401 | 기본적으로 모든 API는 인증 필요 |
| ④ | JWT 검증 활성화 | RS256 서명 검증 | KeyCloak의 공개키로 검증 |

**application.yml 연동:**

```yaml
# gateway/src/main/resources/application.yml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8080/realms/spike-order
          jwk-set-uri: http://localhost:8080/realms/spike-order/protocol/openid-connect/certs
```

| 설정 | 설명 |
|------|------|
| `issuer-uri` | JWT의 `iss` 클레임과 일치해야 함 |
| `jwk-set-uri` | 공개키(JWK)를 가져오는 URL. Gateway가 시작 시 캐싱 |

---

### 5.2 TraceIdFilter - 분산 추적

**파일 위치:** `gateway/src/main/java/com/simiyami/gateway/filter/TraceIdFilter.java`

**실행 순서:** `Ordered.HIGHEST_PRECEDENCE` (가장 먼저)

```java
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 클라이언트가 보낸 Trace ID 확인
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER);

        // 2. 없으면 새로 생성
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        // 3. 요청 헤더에 추가 (Backend로 전달)
        ServerHttpRequest request = exchange.getRequest().mutate()
            .header(TRACE_ID_HEADER, traceId)
            .build();

        // 4. 응답 헤더에도 추가 (클라이언트에게 반환)
        // ServerHttpResponseDecorator 사용 (응답 작성 시 헤더 추가)
        ...
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;  // 가장 먼저 실행
    }
}
```

**왜 이렇게 설계했나?**

```
문제: 마이크로서비스 환경에서 요청 추적이 어려움
┌────────┐    ┌─────────┐    ┌─────────┐    ┌────────┐
│ Client │ → │ Gateway │ → │Order API│ → │ DB     │
└────────┘    └─────────┘    └─────────┘    └────────┘
   "어디서 에러났지?"  "여기?"    "아니 여기?"   "여기인가?"

해결: 모든 서비스가 동일한 Trace ID를 로그에 남김
[abc-123] Gateway: 요청 수신
[abc-123] Order API: 주문 처리 시작
[abc-123] Order API: DB 조회
[abc-123] Order API: 에러 발생!  ← 추적 가능!
```

| 설계 결정 | 이유 |
|----------|------|
| 가장 먼저 실행 | 모든 후속 필터/서비스가 같은 Trace ID 사용 |
| 클라이언트 ID 유지 | 클라이언트가 이미 ID를 가지고 있으면 존중 |
| 응답에도 포함 | 클라이언트가 에러 발생 시 Trace ID로 문의 가능 |

---

### 5.3 IdempotencyKeyFilter - 멱등성 보장

**파일 위치:** `gateway/src/main/java/com/simiyami/gateway/filter/IdempotencyKeyFilter.java`

**실행 순서:** `Ordered.HIGHEST_PRECEDENCE + 1` (TraceId 다음)

```java
@Component
public class IdempotencyKeyFilter implements GlobalFilter, Ordered {

    private static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();

        // POST, PUT, PATCH만 검증 (상태를 변경하는 요청)
        if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            String idempotencyKey = exchange.getRequest().getHeaders()
                .getFirst(IDEMPOTENCY_KEY_HEADER);

            // 없으면 400 Bad Request
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                return writeErrorResponse(exchange,
                    "X-Idempotency-Key header is required for " + method + " requests");
            }

            // UUID 형식 검증
            if (!isValidUUID(idempotencyKey)) {
                return writeErrorResponse(exchange,
                    "X-Idempotency-Key must be a valid UUID");
            }
        }

        return chain.filter(exchange);
    }
}
```

**왜 멱등키가 필요한가?**

```
문제: 네트워크 불안정으로 중복 요청 발생

1. 클라이언트가 주문 요청 전송
2. 서버가 처리 완료, 응답 전송 중 네트워크 끊김
3. 클라이언트는 응답을 못 받아서 "실패"로 인식
4. 클라이언트가 같은 주문 재요청
5. 결과: 중복 주문!

해결: 멱등키로 같은 요청임을 식별

요청 1: POST /orders, X-Idempotency-Key: abc-123 → 주문 생성
요청 2: POST /orders, X-Idempotency-Key: abc-123 → "이미 처리됨" 반환
```

| 설계 결정 | 이유 |
|----------|------|
| POST/PUT/PATCH만 검증 | GET/DELETE는 본래 멱등함 |
| UUID 형식 강제 | 충돌 방지, 클라이언트 실수 방지 |
| Gateway에서 형식만 검증 | 실제 중복 체크는 Order API에서 (Redis 사용) |

---

### 5.4 RateLimitFilter - 요청 제한

**파일 위치:** `gateway/src/main/java/com/simiyami/gateway/filter/RateLimitFilter.java`

**실행 순서:** `0` (Security Filter 이후)

```java
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    // 사용자별 버킷 (인증된 사용자)
    private final Map<String, Bucket> userBuckets = new ConcurrentHashMap<>();

    // IP별 버킷 (미인증 사용자)
    private final Map<String, Bucket> ipBuckets = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
            .map(this::extractUserId)
            .defaultIfEmpty(Optional.empty())
            .flatMap(optionalUserId -> {
                Bucket bucket;

                if (optionalUserId.isPresent()) {
                    // 인증된 사용자: 100 req/min
                    String userId = optionalUserId.get();
                    bucket = userBuckets.computeIfAbsent(userId, k -> createUserBucket());
                } else {
                    // 미인증 사용자: IP당 1000 req/min
                    String clientIp = extractClientIp(exchange);
                    bucket = ipBuckets.computeIfAbsent(clientIp, k -> createIpBucket());
                }

                if (bucket.tryConsume(1)) {
                    return chain.filter(exchange);  // 통과
                } else {
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    return exchange.getResponse().setComplete();  // 429 반환
                }
            });
    }

    // Token Bucket: 100개 토큰, 1분마다 100개 충전
    private Bucket createUserBucket() {
        Bandwidth limit = Bandwidth.classic(100, Refill.greedy(100, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }
}
```

**Token Bucket 알고리즘 설명:**

```
[버킷: 100개 토큰]

요청 1 → 토큰 1개 소모 → [99개 남음] → 통과
요청 2 → 토큰 1개 소모 → [98개 남음] → 통과
...
요청 100 → 토큰 1개 소모 → [0개 남음] → 통과
요청 101 → 토큰 없음 → 429 Too Many Requests

(1분 후)
[버킷: 100개 토큰으로 충전]
요청 102 → 토큰 1개 소모 → [99개 남음] → 통과
```

**왜 이렇게 설계했나?**

| 설계 결정 | 이유 |
|----------|------|
| Security 이후 실행 (order=0) | JWT에서 사용자 ID를 추출해야 하므로 |
| 사용자별 제한 (100/min) | 개인 사용자의 과도한 요청 방지 |
| IP별 제한 (1000/min) | 미인증 상태의 DDoS 방지, 봇 차단 |
| Token Bucket 알고리즘 | 버스트 트래픽 허용하면서도 평균 속도 제한 |

**사용자 ID 추출 로직:**

```java
private Optional<String> extractUserId(Principal principal) {
    if (principal instanceof JwtAuthenticationToken jwtAuth) {
        var jwt = jwtAuth.getToken();

        // 우선순위: sub → preferred_username → jti
        if (jwt.getSubject() != null) return Optional.of(jwt.getSubject());
        if (jwt.getClaimAsString("preferred_username") != null)
            return Optional.of(jwt.getClaimAsString("preferred_username"));
        if (jwt.getId() != null) return Optional.of(jwt.getId());
    }
    return Optional.ofNullable(principal.getName());
}
```

**왜 여러 클레임을 확인하나?**
- KeyCloak 설정에 따라 `sub` 클레임이 없을 수 있음
- `preferred_username`이 더 읽기 쉬운 식별자
- 최후의 수단으로 `jti`(토큰 ID) 사용

---

### 5.5 Order API Security 설정

**파일 위치:** `order-api/src/main/java/com/simiyami/orderapi/config/SecurityConfig.java`

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))  // ①
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> {}));

        return http.build();
    }
}
```

**Gateway vs Order API Security 차이:**

| 항목 | Gateway | Order API |
|------|---------|-----------|
| 프레임워크 | WebFlux (Reactive) | WebMVC (Servlet) |
| 어노테이션 | `@EnableWebFluxSecurity` | `@EnableWebSecurity` |
| 설정 클래스 | `ServerHttpSecurity` | `HttpSecurity` |
| 세션 | 기본적으로 Stateless | 명시적으로 STATELESS 설정 필요 |

**왜 Order API에서도 JWT 검증이 필요한가?**

```
시나리오: Gateway를 우회한 직접 호출

정상 경로:
Client → Gateway(JWT 검증) → Order API

우회 시도:
Client → Order API (직접 호출)  ← 이것도 막아야 함!

따라서 Order API도 독립적으로 JWT를 검증합니다.
"심층 방어(Defense in Depth)" 원칙
```

---

### 5.6 Order API Controller

**파일 위치:** `order-api/src/main/java/com/simiyami/orderapi/controller/OrderController.java`

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "order-api"));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(
            @AuthenticationPrincipal Jwt jwt,  // ① Spring Security가 주입
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        Map<String, Object> response = new HashMap<>();
        // ② JWT에서 사용자 정보 추출
        response.put("userId", jwt.getSubject() != null ? jwt.getSubject() : jwt.getId());
        response.put("username", jwt.getClaimAsString("preferred_username"));
        response.put("email", jwt.getClaimAsString("email"));
        // ③ Gateway에서 전달받은 헤더
        response.put("idempotencyKey", idempotencyKey != null ? idempotencyKey : "not provided");
        response.put("traceId", traceId != null ? traceId : "not provided");

        return ResponseEntity.ok(response);
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testPost(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,  // 필수
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestBody(required = false) Map<String, Object> body) {

        Map<String, Object> response = new HashMap<>();
        response.put("message", "POST request successful");
        response.put("userId", jwt.getSubject() != null ? jwt.getSubject() : jwt.getId());
        response.put("idempotencyKey", idempotencyKey);
        response.put("traceId", traceId != null ? traceId : "not provided");

        return ResponseEntity.ok(response);
    }
}
```

**코드 포인트:**

| 포인트 | 설명 |
|--------|------|
| `@AuthenticationPrincipal Jwt jwt` | Spring Security가 검증된 JWT를 자동 주입 |
| `jwt.getSubject()` | JWT의 `sub` 클레임 (없을 수 있음) |
| `jwt.getClaimAsString("preferred_username")` | 커스텀 클레임 접근 |
| `@RequestHeader` | Gateway에서 주입한 헤더 수신 |

---

## 6. 필터 실행 순서

```
요청 도착
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ TraceIdFilter (order: HIGHEST_PRECEDENCE = -2147483648) │
│ → X-Trace-Id 생성/유지                                   │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ IdempotencyKeyFilter (order: HIGHEST_PRECEDENCE + 1)    │
│ → POST/PUT/PATCH에서 멱등키 검증                         │
│ → 실패 시 400 반환하고 종료                              │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ Spring Security Filter (JWT 검증)                       │
│ → JWT 서명 검증, 만료 확인                               │
│ → 실패 시 401 반환하고 종료                              │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ RateLimitFilter (order: 0)                              │
│ → 인증된 사용자 ID로 Rate Limit 체크                     │
│ → 초과 시 429 반환하고 종료                              │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ Spring Cloud Gateway Routing                            │
│ → Order API로 요청 전달                                  │
└─────────────────────────────────────────────────────────┘
```

**왜 이 순서인가?**

| 순서 | 필터 | 이유 |
|------|------|------|
| 1 | TraceIdFilter | 모든 로그에 Trace ID가 포함되어야 함 |
| 2 | IdempotencyKeyFilter | 불필요한 JWT 검증 전에 빠르게 실패 |
| 3 | Security Filter | 인증 확인 |
| 4 | RateLimitFilter | 사용자 ID가 필요하므로 인증 후 실행 |

---

## 7. 파일 구조 요약

```
spike-order/
├── keycloak/
│   └── realm-export.json          # KeyCloak Realm 설정
│
├── gateway/
│   └── src/main/
│       ├── java/.../gateway/
│       │   ├── GatewayApplication.java
│       │   ├── config/
│       │   │   └── SecurityConfig.java    # JWT 검증 설정
│       │   └── filter/
│       │       ├── TraceIdFilter.java     # 분산 추적
│       │       ├── IdempotencyKeyFilter.java  # 멱등키 검증
│       │       └── RateLimitFilter.java   # Rate Limiting
│       └── resources/
│           └── application.yml            # JWT issuer 설정
│
├── order-api/
│   └── src/main/
│       ├── java/.../orderapi/
│       │   ├── OrderApiApplication.java
│       │   ├── config/
│       │   │   └── SecurityConfig.java    # JWT 검증 설정
│       │   └── controller/
│       │       └── OrderController.java   # API 엔드포인트
│       └── resources/
│           └── application.yml            # JWT issuer 설정
│
└── docs/
    └── flow1-3.md                 # 이 문서
```

---

## 8. 테스트 방법

### 수동 테스트

```bash
# 1. 인프라 시작
docker-compose up -d

# 2. Gateway 시작
./gradlew :gateway:bootRun

# 3. Order API 시작 (새 터미널)
./gradlew :order-api:bootRun

# 4. 토큰 발급
export TOKEN=$(curl -s -X POST http://localhost:8080/realms/spike-order/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=spike-order-client" \
  -d "username=testuser" \
  -d "password=testpass" | jq -r .access_token)

# 5. API 호출
curl -H "Authorization: Bearer $TOKEN" \
     -H "X-Idempotency-Key: $(uuidgen)" \
     http://localhost:8081/api/orders/me | jq .
```

### 자동화 테스트

```bash
# 단위 테스트
./gradlew test

# 통합 테스트 (서비스 실행 중일 때)
./gradlew :gateway:test --tests "*AuthFlowIntegrationTest" -Dintegration.test.enabled=true
```

### 테스트 파일 위치

| 테스트 | 파일 |
|--------|------|
| TraceIdFilter 단위 | `gateway/src/test/.../filter/TraceIdFilterTest.java` |
| IdempotencyKeyFilter 단위 | `gateway/src/test/.../filter/IdempotencyKeyFilterTest.java` |
| RateLimitFilter 단위 | `gateway/src/test/.../filter/RateLimitFilterTest.java` |
| 전체 통합 테스트 | `gateway/src/test/.../integration/AuthFlowIntegrationTest.java` |
| OrderController 단위 | `order-api/src/test/.../controller/OrderControllerTest.java` |

---

## 9. 트러블슈팅

### 401 Unauthorized 응답

**증상:** JWT를 보냈는데 401 반환

**확인 사항:**
1. 토큰이 만료되지 않았는지 확인 (기본 5분)
2. `issuer-uri`가 application.yml과 일치하는지 확인
3. KeyCloak이 실행 중인지 확인

```bash
# 토큰 디코딩으로 만료 시간 확인
echo $TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | jq .exp

# 현재 시간과 비교
date +%s
```

### 400 Bad Request (멱등키)

**증상:** POST 요청에서 400 반환

**확인 사항:**
1. `X-Idempotency-Key` 헤더가 있는지
2. UUID 형식이 맞는지

```bash
# 올바른 형식
curl -H "X-Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" ...

# 잘못된 형식 (400 발생)
curl -H "X-Idempotency-Key: not-a-uuid" ...
```

### 429 Too Many Requests

**증상:** Rate Limit 초과

**해결:**
1. 1분 대기 후 재시도
2. Gateway 재시작으로 버킷 초기화 (개발 환경)

### 500 Internal Server Error

**증상:** 서버 내부 오류

**디버깅:**
1. Gateway/Order API 로그 확인
2. `X-Trace-Id`로 로그 추적

```bash
# 응답에서 Trace ID 확인
curl -v -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/orders/health 2>&1 | grep -i trace

# 로그에서 해당 Trace ID 검색
grep "abc-123-trace-id" gateway.log
```

---

*최종 수정: 2025-01-15*
