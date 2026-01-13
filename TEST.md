# TEST.md

이 문서는 각 기능별 테스트 방법을 정리합니다.

---

## 인증 플로우 (1~3번) 테스트

### 사전 준비

```bash
# 1. Docker로 KeyCloak 실행
docker-compose up -d

# KeyCloak이 준비될 때까지 대기 (약 30초)
# 확인: http://localhost:8080 접속 가능 여부

# 2. Gateway 실행 (터미널 1)
./gradlew :gateway:bootRun

# 3. Order API 실행 (터미널 2)
./gradlew :order-api:bootRun
```

### ① 토큰 요청 → ② JWT 발급 테스트

**테스트 목적**: KeyCloak에서 JWT 토큰을 정상적으로 발급받는지 확인

```bash
# 정상 케이스: testuser로 토큰 발급
curl -s -X POST http://localhost:8080/realms/spike-order/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=spike-order-client" \
  -d "username=testuser" \
  -d "password=testpass" | jq .

# 예상 결과: access_token, refresh_token, expires_in 등 포함된 JSON 응답
```

```bash
# 실패 케이스: 잘못된 비밀번호
curl -s -X POST http://localhost:8080/realms/spike-order/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=spike-order-client" \
  -d "username=testuser" \
  -d "password=wrongpass" | jq .

# 예상 결과: error: "invalid_grant"
```

```bash
# admin 사용자로 토큰 발급
curl -s -X POST http://localhost:8080/realms/spike-order/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=spike-order-client" \
  -d "username=admin" \
  -d "password=adminpass" | jq .
```

### ③ API 요청 (JWT + 멱등키) 테스트

먼저 토큰을 환경변수에 저장:
```bash
export TOKEN=$(curl -s -X POST http://localhost:8080/realms/spike-order/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=spike-order-client" \
  -d "username=testuser" \
  -d "password=testpass" | jq -r .access_token)
```

#### JWT 검증 테스트

```bash
# 정상 케이스: 유효한 JWT로 API 호출
curl -s -H "Authorization: Bearer $TOKEN" \
     http://localhost:8081/api/orders/health | jq .

# 예상 결과: {"status":"UP","service":"order-api"}
```

```bash
# 실패 케이스: JWT 없이 호출
curl -s -w "\nHTTP Status: %{http_code}\n" \
     http://localhost:8081/api/orders/health

# 예상 결과: HTTP Status: 401
```

```bash
# 실패 케이스: 잘못된 JWT
curl -s -w "\nHTTP Status: %{http_code}\n" \
     -H "Authorization: Bearer invalid.token.here" \
     http://localhost:8081/api/orders/health

# 예상 결과: HTTP Status: 401
```

#### 멱등키 검증 테스트

```bash
# 정상 케이스: 유효한 멱등키로 POST 요청
curl -s -w "\nHTTP Status: %{http_code}\n" \
     -X POST \
     -H "Authorization: Bearer $TOKEN" \
     -H "X-Idempotency-Key: $(uuidgen)" \
     -H "Content-Type: application/json" \
     http://localhost:8081/api/orders/test

# 예상 결과: HTTP Status: 200
# {
#   "message": "POST request successful",
#   "userId": "<user-uuid>",
#   "idempotencyKey": "<uuid>",
#   ...
# }
```

```bash
# 실패 케이스: 멱등키 없이 POST 요청
curl -s -w "\nHTTP Status: %{http_code}\n" \
     -X POST \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     http://localhost:8081/api/orders/test

# 예상 결과: HTTP Status: 400 (Gateway에서 차단됨)
```

```bash
# 실패 케이스: 잘못된 형식의 멱등키
curl -s -w "\nHTTP Status: %{http_code}\n" \
     -X POST \
     -H "Authorization: Bearer $TOKEN" \
     -H "X-Idempotency-Key: not-a-uuid" \
     -H "Content-Type: application/json" \
     http://localhost:8081/api/orders/test

# 예상 결과: HTTP Status: 400 (Gateway에서 차단됨)
```

#### Trace ID 테스트

```bash
# Trace ID가 응답 헤더에 포함되는지 확인
curl -s -D - -o /dev/null \
     -H "Authorization: Bearer $TOKEN" \
     http://localhost:8081/api/orders/health | grep -i "x-trace-id"

# 예상 결과: X-Trace-Id: <UUID 형식>
```

```bash
# 클라이언트가 보낸 Trace ID가 유지되는지 확인
curl -s -D - -o /dev/null \
     -H "Authorization: Bearer $TOKEN" \
     -H "X-Trace-Id: my-custom-trace-123" \
     http://localhost:8081/api/orders/health | grep -i "x-trace-id"

# 예상 결과: X-Trace-Id: my-custom-trace-123
```

#### Rate Limiting 테스트

```bash
# 빠르게 여러 번 호출하여 Rate Limit 확인 (100회 초과 시 429)
for i in {1..110}; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer $TOKEN" \
    http://localhost:8081/api/orders/health)
  echo "Request $i: $STATUS"
  if [ "$STATUS" == "429" ]; then
    echo "Rate limited at request $i"
    break
  fi
done
```

#### 사용자 정보 조회 테스트

```bash
# JWT에서 사용자 정보 추출 확인
curl -s -H "Authorization: Bearer $TOKEN" \
     -H "X-Idempotency-Key: $(uuidgen)" \
     http://localhost:8081/api/orders/me | jq .

# 예상 결과:
# {
#   "userId": "<user-uuid>",
#   "username": "testuser",
#   "email": "testuser@example.com",
#   "idempotencyKey": "<uuid>",
#   "traceId": "<uuid>"
# }
```

### 통합 테스트 실행

```bash
# 모든 테스트 실행
./gradlew test

# Gateway 통합 테스트만 실행
./gradlew :gateway:test --tests "*IntegrationTest"

# Order API 통합 테스트만 실행
./gradlew :order-api:test --tests "*IntegrationTest"
```

### 정리

```bash
# 애플리케이션 종료 후 Docker 정리
docker-compose down

# 볼륨까지 삭제 (데이터 초기화)
docker-compose down -v
```

---

## 테스트 계정 정보

| 사용자 | 비밀번호 | 역할 | 이메일 |
|--------|----------|------|--------|
| testuser | testpass | user | testuser@example.com |
| admin | adminpass | user, admin | admin@example.com |

## KeyCloak 관리자 콘솔

- URL: http://localhost:8080/admin
- 계정: admin / admin
