# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a delivery app order system architecture designed to handle **spike order scenarios** (대량 주문 이벤트) - situations where thousands of orders per second flood a single store during promotional events.

## Project Structure

Multi-module Gradle project:
```
spike-order/
├── gateway/          # API Gateway (Spring Cloud Gateway)
├── order-api/        # Order API service
├── docker-compose.yml
└── keycloak/         # KeyCloak realm configuration
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

# Run a single test class
./gradlew :order-api:test --tests "com.simiyami.orderapi.OrderApiApplicationTests"
```

## Running the Application

```bash
# 1. Start KeyCloak and PostgreSQL
docker-compose up -d

# 2. Run Gateway (port 8081)
./gradlew :gateway:bootRun

# 3. Run Order API (port 8082) - in another terminal
./gradlew :order-api:bootRun

# 4. Get JWT token from KeyCloak
curl -X POST http://localhost:8080/realms/spike-order/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=spike-order-client" \
  -d "username=testuser" \
  -d "password=testpass"

# 5. Call API through Gateway
curl -H "Authorization: Bearer <JWT_TOKEN>" \
     -H "X-Idempotency-Key: $(uuidgen)" \
     http://localhost:8081/api/orders/me
```

## Port Configuration

- KeyCloak: 8080
- API Gateway: 8081
- Order API: 8082

## Tech Stack

- **Java 21** with **Spring Boot 3.4.1**
- **Spring Cloud 2024.0.0** (Gateway)
- Gradle multi-module build
- JUnit 5 for testing
- Docker Compose for local infrastructure

## Architecture Principles

The system is designed around these core patterns (documented in detail in README.md):

1. **Hot Path Minimization** - Only essential operations (order save + Outbox record) are synchronous; payment/notifications are async via Kafka
2. **CQRS** - Read/write separation using Replica DB and Elasticsearch
3. **Outbox Pattern** - Guarantees atomicity between DB transactions and event publishing
4. **SAGA Pattern** - Compensating transactions for distributed failure scenarios (e.g., payment failure → inventory rollback → order cancellation)
5. **Idempotency** - UUID-based idempotency keys with Redis TTL to prevent duplicate orders
6. **Singleflight Pattern** - Prevents cache stampede using Redis distributed locks

## Implemented Features (Authentication Flow)

### Gateway Module (`gateway/`)
- **JWT Validation**: OAuth2 Resource Server with KeyCloak integration
- **Rate Limiting**: Token Bucket algorithm (100 req/min per user, 1000 req/min per IP)
- **Idempotency Key Filter**: Validates UUID format for POST/PUT/PATCH requests
- **Trace ID Filter**: Injects X-Trace-Id header for distributed tracing

### Order API Module (`order-api/`)
- **JWT Validation**: OAuth2 Resource Server configuration
- **Test Endpoints**: `/api/orders/health`, `/api/orders/me`

## Testing

기능별 상세 테스트 방법은 [TEST.md](docs/TEST.md) 참조.

```bash
# 모든 유닛 테스트 실행
./gradlew test

# Gateway 필터 테스트
./gradlew :gateway:test --tests "*FilterTest"

# Order API 컨트롤러 테스트
./gradlew :order-api:test --tests "*ControllerTest"
```

## Response Time Target

Hot path operations should complete within **200ms**.
