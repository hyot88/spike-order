# 테스트 환경 설정

> 테스트 실행 전 필요한 환경 설정과 계정 정보를 정리합니다.

---

## 사전 준비

### 1. 인프라 실행

```bash
# Docker로 KeyCloak 실행
docker-compose up -d

# KeyCloak 준비 확인 (약 30초 소요)
curl -s http://localhost:8080/realms/spike-order | jq .realm
# 출력: "spike-order" 면 준비 완료
```

### 2. 애플리케이션 실행

```bash
# 터미널 1: Gateway (8081)
./gradlew :gateway:bootRun

# 터미널 2: Order API (8082)
./gradlew :order-api:bootRun
```

### 3. 서비스 상태 확인

```bash
# KeyCloak
curl -s http://localhost:8080/realms/spike-order > /dev/null && echo "KeyCloak: OK" || echo "KeyCloak: FAIL"

# Gateway
curl -s http://localhost:8081/actuator/health | jq -r '.status'

# Order API
curl -s http://localhost:8082/actuator/health | jq -r '.status'
```

---

## 포트 정보

| 서비스 | 포트 | 용도 |
|--------|------|------|
| KeyCloak | 8080 | 인증 서버 |
| Gateway | 8081 | API Gateway |
| Order API | 8082 | 주문 서비스 |

---

## 테스트 계정

### 일반 사용자

| 항목 | 값 |
|------|-----|
| Username | `testuser` |
| Password | `testpass` |
| Email | testuser@example.com |
| Role | user |

### 관리자

| 항목 | 값 |
|------|-----|
| Username | `admin` |
| Password | `adminpass` |
| Email | admin@example.com |
| Role | user, admin |

---

## KeyCloak 관리자 콘솔

| 항목 | 값 |
|------|-----|
| URL | http://localhost:8080/admin |
| Username | `admin` |
| Password | `admin` |

### 주요 메뉴

- **Users**: 사용자 관리
- **Clients**: 클라이언트 앱 설정
- **Realm Settings**: Realm 설정
- **Sessions**: 활성 세션 확인

---

## 토큰 발급 (Quick Reference)

```bash
# testuser 토큰 발급
export TOKEN=$(curl -s -X POST http://localhost:8080/realms/spike-order/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=spike-order-client" \
  -d "username=testuser" \
  -d "password=testpass" | jq -r .access_token)

# 토큰 확인
echo $TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | jq .
```

---

## 정리

```bash
# 애플리케이션 종료: Ctrl+C

# Docker 컨테이너 중지
docker-compose down

# 데이터까지 완전 삭제
docker-compose down -v
```

---

## 트러블슈팅

### KeyCloak 접속 안 됨

```bash
# 컨테이너 상태 확인
docker-compose ps

# 로그 확인
docker-compose logs keycloak
```

### 토큰 발급 실패

```bash
# Realm 존재 확인
curl -s http://localhost:8080/realms/spike-order | jq .realm

# 클라이언트 확인 (관리 콘솔에서)
# Clients > spike-order-client > Settings
```

### 포트 충돌

```bash
# 사용 중인 포트 확인
lsof -i :8080
lsof -i :8081
lsof -i :8082
```
