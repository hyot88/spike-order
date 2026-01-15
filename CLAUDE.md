# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a delivery app order system architecture designed to handle **spike order scenarios** (대량 주문 이벤트) - situations where thousands of orders per second flood a single store during promotional events.

## Project Structure

Multi-module Gradle project:
```
spike-order/
├── gateway/                 # API Gateway (Spring Cloud Gateway)
│   └── src/main/java/.../
│       ├── config/          # SecurityConfig (JWT 검증)
│       └── filter/          # TraceId, Idempotency, RateLimit 필터
├── order-api/               # Order API service
│   └── src/main/java/.../
│       ├── config/          # SecurityConfig
│       └── controller/      # OrderController
├── keycloak/                # KeyCloak realm configuration
│   └── realm-export.json
├── docs/                    # Documentation
│   ├── flow1-3.md           # 인증 플로우 코드 가이드
│   ├── TEST.md              # 테스트 케이스
│   └── test-setup.md        # 테스트 환경 설정
└── docker-compose.yml
```

## Build Commands

```bash
# Build all modules
./gradlew build

# Build specific module
./gradlew :gateway:build
./gradlew :order-api:build

# Run specific module
./gradlew :gateway:bootRun
./gradlew :order-api:bootRun

# Run all tests
./gradlew test

# Run tests for specific module
./gradlew :gateway:test
./gradlew :order-api:test

# Run integration tests (requires running services)
./gradlew :gateway:test --tests "*AuthFlowIntegrationTest" -Dintegration.test.enabled=true
```

## Running the Application

```bash
# 1. Start KeyCloak
docker-compose up -d

# 2. Run Gateway (port 8081)
./gradlew :gateway:bootRun

# 3. Run Order API (port 8082) - in another terminal
./gradlew :order-api:bootRun

# 4. Get JWT token from KeyCloak
export TOKEN=$(curl -s -X POST http://localhost:8080/realms/spike-order/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=spike-order-client" \
  -d "username=testuser" \
  -d "password=testpass" | jq -r .access_token)

# 5. Call API through Gateway
curl -H "Authorization: Bearer $TOKEN" \
     -H "X-Idempotency-Key: $(uuidgen)" \
     http://localhost:8081/api/orders/me
```

## Port Configuration

| Service | Port |
|---------|------|
| KeyCloak | 8080 |
| API Gateway | 8081 |
| Order API | 8082 |

## Tech Stack

- **Java 21** with **Spring Boot 3.4.1**
- **Spring Cloud 2024.0.0** (Gateway)
- **Spring Security OAuth2 Resource Server** (JWT validation)
- **Bucket4j** (Rate Limiting)
- Gradle multi-module build
- JUnit 5 for testing
- Docker Compose for local infrastructure

## Architecture Principles

The system is designed around these core patterns (documented in detail in README.md):

1. **Hot Path Minimization** - Only essential operations (order save + Outbox record) are synchronous; payment/notifications are async via Kafka
2. **CQRS** - Read/write separation using Replica DB and Elasticsearch
3. **Outbox Pattern** - Guarantees atomicity between DB transactions and event publishing
4. **SAGA Pattern** - Compensating transactions for distributed failure scenarios
5. **Idempotency** - UUID-based idempotency keys to prevent duplicate orders
6. **Singleflight Pattern** - Prevents cache stampede using Redis distributed locks

## Implemented Features

### Flow 1-3: 인증 플로우 (✅ Complete)

상세 가이드: [docs/flow1-3.md](docs/flow1-3.md)

#### Gateway Module (`gateway/`)

| Component | File | Description |
|-----------|------|-------------|
| SecurityConfig | `config/SecurityConfig.java` | JWT 검증, OAuth2 Resource Server |
| TraceIdFilter | `filter/TraceIdFilter.java` | X-Trace-Id 헤더 주입 (분산 추적) |
| IdempotencyKeyFilter | `filter/IdempotencyKeyFilter.java` | POST/PUT/PATCH 멱등키 검증 |
| RateLimitFilter | `filter/RateLimitFilter.java` | Token Bucket (100 req/min per user) |

#### Order API Module (`order-api/`)

| Component | File | Description |
|-----------|------|-------------|
| SecurityConfig | `config/SecurityConfig.java` | JWT 검증 |
| OrderController | `controller/OrderController.java` | API 엔드포인트 |

#### API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/orders/health` | Required | 헬스체크 |
| GET | `/api/orders/me` | Required | 현재 사용자 정보 |
| POST | `/api/orders/test` | Required + Idempotency Key | 테스트 엔드포인트 |
| GET | `/actuator/health` | Public | Actuator 헬스체크 |

### Flow 4-6: Gateway 처리 (🔲 Not Started)
### Flow 7-12: 주문 핫패스 (🔲 Not Started)

## Testing

### Documentation

- **테스트 환경 설정**: [docs/test-setup.md](docs/test-setup.md)
- **테스트 케이스**: [docs/TEST.md](docs/TEST.md)

### Test Commands

```bash
# 단위 테스트
./gradlew test

# Gateway 필터 테스트
./gradlew :gateway:test --tests "*FilterTest"

# Order API 컨트롤러 테스트
./gradlew :order-api:test --tests "*ControllerTest"

# 통합 테스트 (서비스 실행 필요)
./gradlew :gateway:test --tests "*AuthFlowIntegrationTest" -Dintegration.test.enabled=true
```

### Test Files

| Test | Location |
|------|----------|
| TraceIdFilterTest | `gateway/src/test/.../filter/` |
| IdempotencyKeyFilterTest | `gateway/src/test/.../filter/` |
| RateLimitFilterTest | `gateway/src/test/.../filter/` |
| AuthFlowIntegrationTest | `gateway/src/test/.../integration/` |
| OrderControllerTest | `order-api/src/test/.../controller/` |

## Test Accounts

| User | Password | Role |
|------|----------|------|
| testuser | testpass | user |
| admin | adminpass | user, admin |

## Response Time Target

Hot path operations should complete within **200ms**.
