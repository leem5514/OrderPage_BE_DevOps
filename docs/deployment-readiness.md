# 백엔드 배포 준비 점검 — 2026-11-06

기준 원격 main: `15de89623b441bd97921e42a80f6028572407f1b`.
작업 브랜치: `codex/deployment-readiness`. 기존 로컬의 미커밋 성능 실험은 가져오지 않았다.

## 확인과 최소 수정

| 항목 | 기존 문제 | 변경 |
|---|---|---|
| 공개 회원가입 | 요청 body의 ADMIN 권한이 그대로 저장됨 | Controller에서 USER로 제한. 수정 전 통합 테스트 실패, 수정 후 차단 확인 |
| 관리자 초기화 | 공개된 고정 비밀번호로 자동 생성 | 기본 비활성, 명시적 초기화와 외부 비밀번호 주입만 허용 |
| 관리자 SSE | 수신자가 admin@test.com으로 고정 | 초기화/주문 알림이 같은 ADMIN_EMAIL 사용. 계정 변경 후 알림 누락 방지 |
| AWS 인증 | 고정 키만 읽어 IRSA 사용 불가 | SDK 기본 자격증명 체인 + 동일 버전 STS 모듈 |
| Redis | 수동 연결 팩토리가 TLS/password를 읽지 않음 | DB 1/2/3 모두 TLS·암호 반영. DB 번호/직렬화 유지 |
| RabbitMQ TLS | 구버전 클라이언트의 인자 없는 TLS 설정이 서버 인증서를 검증하지 않음 | JVM 신뢰 저장소와 호스트명 검증 |
| JWT 필터 | 뒤쪽 업무 처리 예외까지 401로 덮을 수 있음 | 토큰 검증만 catch, 서버 장애의 정상 오류 처리 유지 |

재고 알고리즘, pool/thread/consumer 수, 조회 성능은 수정하지 않았다. 재고 모드는 기존 REDIS_ASYNC를 유지한다.

## 운영 설정 계약

- DB_URL/DB_USERNAME/DB_PASSWORD: RDS endpoint와 앱 계정. JDBC TLS 옵션은 실제 드라이버/인증서 기준으로 다음 단계에서 검증한다.
- REDIS_HOST/REDIS_PORT, REDIS_SSL_ENABLED=true, REDIS_PASSWORD: ElastiCache TLS + AUTH. DB 1/2/3 때문에 cluster mode disabled가 필요하다. ACL username 방식까지 구현한 것은 아니다.
- RABBITMQ_HOST, RABBITMQ_PORT=5671, RABBITMQ_SSL_ENABLED=true, RABBITMQ_USERNAME/PASSWORD/VHOST: Amazon MQ 설정. 인증서 검증 실패를 무시하지 않는다.
- AWS_REGION=ap-northeast-2, AWS_S3_BUCKET: S3 대상. EKS에는 고정 키 대신 ServiceAccount IRSA를 연결한다. SDK 2.17.58 기준이며 EKS Pod Identity를 검증한 것은 아니다.
- 로컬 키 이름은 AWS_ACCESS_KEY/AWS_SECRET_KEY에서 표준 AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY로 바뀐다. 임시 자격증명에는 AWS_SESSION_TOKEN도 필요하다. 실제 키는 Git에 넣지 않는다.
- CORS_ALLOWED_ORIGINS: 실제 Vercel origin을 명시한다. 후보는 https://devops.auctionbid.shop이며 DNS는 아직 변경하지 않았다. Preview origin도 개별 허용한다.
- JWT_SECRET_KEY/JWT_SECRET_KEY_RT: 모든 replicas에서 동일한 외부 Secret을 사용한다. 저장소의 로컬 기본값으로 공개 배포하지 않는다.

일반 Spring Boot 실행은 .env를 자동으로 로드하지 않는다. Compose는 치환에 사용하며 IntelliJ/셸/CI에는 실행 환경변수로 주입한다.

## 관리자 최초 생성

1. 외부 요청을 열기 전 빈 DB에서 한 인스턴스만 실행한다.
2. ADMIN_EMAIL과 임의 비밀번호를 준비하고 BOOTSTRAP_ADMIN_ENABLED=true, BOOTSTRAP_ADMIN_PASSWORD를 Secret으로 주입한다.
3. 생성/로그인을 확인하면 ENABLED=false로 바꾸고 초기화 비밀번호 변수를 제거한다.
4. ADMIN_EMAIL은 주문 SSE 수신자 설정이므로 유지한다.

동일 이메일이 이미 있으면 권한/비밀번호를 덮어쓰지 않는다. 기존 DB의 고정 관리자 계정은 이번 코드로 삭제되지 않으므로 공개 연결 전에 별도로 확인한다. 다중 Pod에서 동시에 초기화하지 않는다.

## 검증과 한계

Windows, JDK 17, Gradle wrapper 8.8, Java source target 11에서 실행했다.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:GRADLE_USER_HOME=Join-Path (Get-Location) '.gradle-user-home'
.\gradlew.bat --no-daemon --console=plain test bootJar
docker compose config --quiet
```

- 수정 전 기존 14개 테스트와 bootJar 성공.
- 보안 테스트 추가 후 기존 코드에서 ADMIN 가입/기본 관리자 생성 2개 실패 재현.
- 수정 후 전체 20개 테스트, bootJar, Compose 구성 검사 성공.
- H2/mock 기반 검증이다. 실제 MariaDB/ElastiCache/MQ/S3 왕복 검증을 대신하지 않는다.
- Docker Desktop Linux 엔진 pipe가 없어 Docker build/Compose 기동은 수행하지 못했다. Dockerfile의 Java 11/Linux 런타임 검증도 남아 있다.
- 기본 실행의 DB/Redis/MQ 연결, 로그인·주문·업로드, IRSA STS와 TLS handshake는 서비스 준비 후 확인한다.

## 다음 인프라 단계의 배포 조건

1. Prometheus endpoint는 익명 403이다. annotation만으로 수집되지 않는다. 내부 관리 경로의 인증/포트/노출 범위를 함께 설계한다.
2. 기존 liveness/readiness가 모두 /actuator/health다. DB 장애로 전체 Pod를 재시작하지 않도록 상태 그룹을 분리한다.
3. S3 getUrl()은 서명 URL이 아니다. private bucket에 업로드만 허용하면 브라우저 이미지 조회는 403이다. 제한된 객체 읽기 제공 방식을 확정한다.
4. ddl-auto=update를 다중 Pod에서 무심코 실행하지 않는다. 최초 schema 준비와 후속 validate 절차를 정한다.
5. 외부 의존성 장애/중복 메시지까지의 재고 정합성은 입증하지 않았다. 부하는 먼저 읽기 API로 시작하고 주문 실험은 정합성 검증을 거친다.

## 공식 참고

- [SDK 기본 자격증명 체인](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html)
- [Web Identity의 STS 의존성](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/troubleshooting.html)
- [Spring Data Redis 2.7.11 TLS](https://docs.spring.io/spring-data/redis/docs/2.7.11/api/org/springframework/data/redis/connection/lettuce/LettuceClientConfiguration.html)
- [RabbitMQ TLS 및 호스트명 검증](https://www.rabbitmq.com/docs/ssl)
