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

현재 테스트는 실제 MariaDB 설정에 의존하므로, 다음 단계에서 test profile 또는 Testcontainers 기반으로 안정화합니다.

## Local Docker Compose

```bash
docker compose up -d
```

포함 서비스:

- Backend API
- MariaDB
- Redis
- RabbitMQ Management

## Environment Variables

로컬/배포 환경에서는 `.env.example`을 기준으로 값을 구성합니다.

```text
DB_URL=jdbc:mariadb://localhost:3306/ordersystem
DB_USERNAME=root
DB_PASSWORD=change-me
REDIS_HOST=localhost
RABBITMQ_HOST=localhost
JWT_SECRET_KEY=change-me
JWT_SECRET_KEY_RT=change-me
AWS_ACCESS_KEY=change-me
AWS_SECRET_KEY=change-me
AWS_REGION=ap-northeast-2
AWS_S3_BUCKET=order-system-bucket
```

## DevOps Metrics

이 백엔드는 다음 지표를 만들기 좋은 구조입니다.

- 동시 주문 TPS
- p95/p99 API latency
- oversell 발생 여부
- Redis 기반 재고 차감 전후 비교
- RabbitMQ consumer 수에 따른 queue lag 비교
- HPA 적용 전후 error rate 비교
