# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a delivery app order system architecture designed to handle **spike order scenarios** (대량 주문 이벤트) - situations where thousands of orders per second flood a single store during promotional events.

## Build Commands

```bash
# Build the project
./gradlew build

# Run the application
./gradlew bootRun

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.simiyami.spikeorder.SpikeOrderApplicationTests"

# Run a single test method
./gradlew test --tests "com.simiyami.spikeorder.SpikeOrderApplicationTests.contextLoads"

# Clean and build
./gradlew clean build
```

## Tech Stack

- **Java 21** with **Spring Boot 4.0.1**
- Gradle build system
- JUnit 5 for testing

## Architecture Principles

The system is designed around these core patterns (documented in detail in README.md):

1. **Hot Path Minimization** - Only essential operations (order save + Outbox record) are synchronous; payment/notifications are async via Kafka
2. **CQRS** - Read/write separation using Replica DB and Elasticsearch
3. **Outbox Pattern** - Guarantees atomicity between DB transactions and event publishing
4. **SAGA Pattern** - Compensating transactions for distributed failure scenarios (e.g., payment failure → inventory rollback → order cancellation)
5. **Idempotency** - UUID-based idempotency keys with Redis TTL to prevent duplicate orders
6. **Singleflight Pattern** - Prevents cache stampede using Redis distributed locks

## Key Components (Target Architecture)

- **API Gateway**: Rate limiting (Token Bucket), JWT validation, Trace ID injection
- **Eureka**: Service discovery with client-side load balancing
- **Redis**: Caching, distributed locks, Pub/Sub for cache invalidation
- **Kafka**: Event streaming with ISR replication (min.insync.replicas: 2, acks: all)
- **Flink**: Real-time stream processing with RocksDB state backend
- **Resilience4j**: Circuit breaker, retry, time limiter for external services (PG gateway)
- **Spring Data JPA**: ORM with Repository pattern, single transaction for Order + Outbox

## Response Time Target

Hot path operations should complete within **200ms**.
