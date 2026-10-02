# OrderPage BE DevOps

Spring Boot 기반 주문 서비스 백엔드입니다.

이 레포지토리는 DevOps 포트폴리오의 애플리케이션 배포 대상입니다. Jenkins에서 테스트와 Docker 이미지 빌드를 수행하고, 이미지는 Amazon ECR에 push한 뒤 Infra 레포지토리의 Kubernetes manifest를 통해 EKS에 배포합니다.

## Tech Stack

- Java 11
- Spring Boot 2.7
- Spring Data JPA
- Spring Security + JWT
- MariaDB
- Redis
- RabbitMQ
- AWS S3 SDK
- Docker

## Local Build

```bash
./gradlew test
./gradlew bootJar
```

테스트는 `test` profile에서 H2 in-memory database를 사용합니다. CI에서도 외부 MariaDB 없이 기본 Spring context 검증이 가능합니다.

## Local Docker Compose

Docker Desktop을 먼저 실행한 뒤 아래 명령을 실행합니다.

```bash
docker compose up -d
```

포함 서비스:

- Backend API
- MariaDB
- Redis
- RabbitMQ Management

Smoke test:

```powershell
.\scripts\compose-smoke.ps1
```

검증 대상:

- `/actuator/health`
- `/product/list`

정리:

```bash
docker compose down
```

## Environment Variables

로컬/배포 환경에서는 `.env.example`을 기준으로 값을 구성합니다.

```text
DB_URL=jdbc:mariadb://localhost:3306/ordersystem
DB_USERNAME=root
DB_PASSWORD=change-me
REDIS_HOST=localhost
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_SSL_ENABLED=false
ORDER_STOCK_PROCESSING_MODE=REDIS_ASYNC
JWT_SECRET_KEY=change-me
JWT_SECRET_KEY_RT=change-me
AWS_ACCESS_KEY=change-me
AWS_SECRET_KEY=change-me
AWS_REGION=ap-northeast-2
AWS_S3_BUCKET=order-system-bucket
```

Amazon MQ for RabbitMQ를 사용할 때는 `RABBITMQ_PORT=5671`, `RABBITMQ_SSL_ENABLED=true`로 설정합니다.

### Stock Processing Comparison

`ORDER_STOCK_PROCESSING_MODE`로 동일한 주문 API의 재고 처리 방식만 전환합니다.

| 값 | 비교군 | 처리 방식 |
|---|---|---|
| `RDB_SYNC` | Before | RDB 비관적 행 잠금 안에서 재고 검증과 차감을 동기 처리 |
| `REDIS_ASYNC` | After | Redis Lua 원자 차감 후 RabbitMQ를 통해 RDB에 비동기 반영 |

실험할 때는 모드마다 같은 상품·재고·VU·실행시간을 사용하고, 실행 사이에 DB와 Redis를 초기화합니다. 비교 지표는 주문 TPS, p95/p99, 오류율, 초과 판매 수, DB Lock 대기시간이며 `REDIS_ASYNC`에서는 Queue Lag와 최종 RDB/Redis 재고 일치 여부도 기록합니다.

## DevOps Metrics

이 백엔드는 다음 지표를 만들기 좋은 구조입니다.

- 동시 주문 TPS
- p95/p99 API latency
- oversell 발생 여부
- Redis 기반 재고 차감 전후 비교
- RabbitMQ consumer 수에 따른 queue lag 비교
- HPA 적용 전후 error rate 비교

## Observability

Spring Boot Actuator와 Prometheus registry를 추가했습니다.

노출 대상:

- `/actuator/health`
- `/actuator/info`
- `/actuator/metrics`
- `/actuator/prometheus`

Prometheus/Grafana 연동은 Infra 레포지토리에서 진행합니다.
