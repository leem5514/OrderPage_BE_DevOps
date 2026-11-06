# OrderPage BE DevOps

Spring Boot 기반 주문 서비스 백엔드입니다.

2026-11-06 새 요구사항에 따른 변경·검증·남은 배포 조건은 [배포 점검 기록](docs/deployment-readiness.md)을 참고하세요. 아직 AWS 배포 완료 상태가 아닙니다.

Java 11/Linux 이미지 빌드와 로컬 서비스 통합 확인 13개는 [Docker 실기동 기록](docs/container-verification.md)에 정리했습니다. 기존 개발 DB를 보존하는 `compose.verify.yaml`과 `scripts/container-smoke.mjs`로 재현할 수 있습니다.

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
AWS_ACCESS_KEY_ID=change-me
AWS_SECRET_ACCESS_KEY=change-me
AWS_REGION=ap-northeast-2
AWS_S3_BUCKET=order-system-bucket
```

Amazon MQ for RabbitMQ를 사용할 때는 `RABBITMQ_PORT=5671`, `RABBITMQ_SSL_ENABLED=true`로 설정합니다.

### 기존 재고 처리 모드 (이번 실험에서는 고정)

`ORDER_STOCK_PROCESSING_MODE`로 동일한 주문 API의 재고 처리 방식만 전환합니다.

| 값 | 비교군 | 처리 방식 |
|---|---|---|
| `RDB_SYNC` | Before | RDB 비관적 행 잠금 안에서 재고 검증과 차감을 동기 처리 |
| `REDIS_ASYNC` | After | Redis Lua 원자 차감 후 RabbitMQ를 통해 RDB에 비동기 반영 |

위 모드는 기존 기능입니다. 새 프로젝트에서는 REDIS_ASYNC를 고정하고 배포/운영 조건만 비교합니다. 재고 알고리즘 개선이나 모드 간 성능 비교는 수행하지 않습니다.

## DevOps Metrics

이번 프로젝트의 비교 실험은 다음과 같습니다.

- Recreate vs RollingUpdate + readiness: 배포 실패율과 중단 시간
- 수동 배포 vs Jenkins + Argo CD: 배포 시간, 실패율, 복구 시간
- 고정 자원 vs 실측 requests: CPU/메모리, p95, 처리량
- 고정 replicas vs HPA: p95, RPS, 5xx, 증설 시간
- CloudWatch + SNS: 장애 발생부터 탐지·수신·복구까지의 시간

## Observability

Spring Boot Actuator와 Prometheus registry를 추가했습니다.

노출 대상:

- `/actuator/health`
- `/actuator/info`
- `/actuator/metrics`
- `/actuator/prometheus`

Prometheus/Grafana 연동은 Infra 레포지토리에서 진행합니다.
