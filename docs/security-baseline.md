# 배포 전 백엔드 보안 기반 갱신

2026-11-17. 주문 검증 PR #5를 병합한 뒤, 지원 종료된 실행 기반과 긴 비밀번호 검증 문제를 수정했다. 성능 최적화나 주문 처리 방식 변경을 목표로 하지 않았다.

## 왜 버전 숫자만 바꾸지 않았나

기존 Spring Boot 2.7.11 / Java 11에는 오래된 Spring Security·Tomcat·Jackson 등이 연결돼 있었다. 이번에는 공개 안정 버전 **Spring Boot 4.1.2 / Java 21 LTS / Gradle 8.14.4**로 맞췄다. Docker 빌드·실행과 Jenkins 테스트도 Java 21을 사용한다. Gradle 배포 ZIP은 공식 SHA-256으로 확인한다.

Boot의 기본 의존성 조합만으로 끝나지는 않았다. 재검사에서 RabbitMQ 클라이언트 5.30.0과 MariaDB JDBC 3.5.10에 권고 14개가 남았다. 같은 주 버전의 **RabbitMQ 5.37.1 / MariaDB JDBC 3.5.11**로 지정한 이유다. JJWT는 0.13.0의 API/구현/JSON 모듈로 분리하고, S3·STS는 AWS SDK BOM 2.59.2로 맞췄다.

업그레이드에 필요한 변경은 다음과 같다.

| 변경 | 유지하려는 동작 |
|---|---|
| `javax.persistence/validation/servlet` → `jakarta.*` | 기존 엔티티·검증·HTTP API. Java SE의 `javax.crypto`, `javax.net.ssl`은 유지 |
| Security의 lambda 설정·EnableMethodSecurity | 공개 URL, 익명 요청 거부, ADMIN 메서드 권한 |
| Redis 속성 `spring.data.redis`, Jackson 3 변환기 | TLS/인증, refresh token 문자열, 재고 숫자, SSE DTO |
| RabbitMQ의 Jackson 3 변환기 | 재고 이벤트 JSON. 허용 타입은 주문 DTO 패키지로 제한 |
| Jackson 2 기본 표현 호환 설정 | Vue가 읽는 응답 필드·enum 표현. 실제 본문은 Jackson 3 사용 |
| 고정 이름 `app.jar` | Docker가 일반 JAR 대신 실행 가능한 bootJar 하나만 복사 |

## 비밀번호는 무엇을 막는가

BCrypt에는 UTF-8 **72바이트** 한도가 있다. 72자와는 다르므로 한글 24자는 경계에 해당한다. 가입·로그인·변경은 기존 최소 8자와 새 최대 72바이트 규칙을 함께 적용한다. 입력을 자르거나 정규화하지 않는다. 잘못된 요청은 DB 조회·저장 전에 400으로 거부한다.

실측에서는 업데이트한 기본 PasswordEncoder에서도 앞 72바이트가 같은 초과 입력을 허용했다. 따라서 버전 갱신만으로 해결됐다고 판단하지 않았다. 공통 `BoundedPasswordEncoder`도 초과 입력의 `matches()`를 false로 반환하고 `encode()`를 거부한다. 서비스 입력 검사는 API의 400 응답을, 공통 encoder는 다른 호출 경로의 제한을 담당한다.

정상 범위의 기존 `{bcrypt}` 해시는 그대로 비교한다. 과거 72바이트 초과 비밀번호를 사용한 계정이 있다면 이번 정책으로 로그인이 거부된다. 해당 계정은 소유자 확인을 거친 별도 복구가 필요하다. 비밀번호 변경 후 기존 refresh token 회수 정책은 이번 수정 범위 밖이며 실사용 계정 도입 전 남은 항목이다.

## JWT 전환 시 주의할 점

서명 키는 UTF-8 원문 바이트로 명시적으로 해석한다. 32바이트 미만 키나 access/refresh에 같은 키를 쓰면 시작 시 거부한다. 실제 배포는 Infra 검사대로 **서로 다른 난수 64자 이상**을 사용한다. 길이만으로 무작위성을 보장하지는 않는다.

기존 JJWT 문자열 API의 암묵적 Base64 해석과 달라 **이전 토큰은 재로그인이 필요하다**. 아직 AWS 공개 배포 전인 이번 전환에서 적용한다. 키는 Secret으로만 넣고, 추적 중인 예제·설정 파일에 실제 값을 쓰지 않는다. 로컬 Compose 키는 공개된 검증 전용 값이다.

서명 알고리즘은 HS256으로 제한한다. 만료·subject·role을 확인하며, refresh token으로 일반 API를 인증하거나 access token으로 재발급할 수 없다. refresh 재발급에는 Redis에 저장된 토큰 일치도 필요하다. 파서 오류 원문은 로그·응답에 넣지 않는다.

## 무엇으로 확인했나

| 검증 | 결과·범위 |
|---|---|
| Java 21 `test bootJar` | 68건 통과, 실패·오류·건너뜀 0건. 기존 주문 검사와 비밀번호·JWT·직렬화·권한 검사 포함 |
| Linux Docker 이미지 빌드 | Temurin 21 JDK → JRE, 실제 JAR 기동 |
| `container-smoke.mjs` | 13개 통과. 실제 MariaDB·Redis·RabbitMQ, 로그인/재발급·주문·CORS·SSE 연결 |
| `security-smoke.mjs` | 6개 통과. 72바이트 경계, 초과 입력 거부, 토큰 용도 분리, 비밀번호 변경, Prometheus HTTP histogram/JVM 지표 |
| `lifecycle-smoke.mjs` | DB 중지 시 liveness 200/readiness 503, 복구 시 앱 재시작 없이 readiness 200. SSE 연결 중 0.615초·유휴 1.034초 종료, exit 143/SIGTERM만 확인. 로컬 단일 관측이며 성능 개선율이 아님 |
| 실행 의존성 OSV 조회 | 174개 Maven 좌표, 일치 모듈 0개 / 고유 권고 ID 0개. 조회 시점의 버전 범위 검사 |

이전 조사에서는 129개 모듈 중 34개, 고유 권고 ID 187개가 일치했다. 의존성 집합과 조사 시점이 달라졌으므로 이를 공격 가능성 감소율이나 성능 개선율로 표현하지 않는다. OSV 0건은 미공개 취약점·업무 로직·컨테이너 OS·실제 IAM/TLS까지 안전하다는 증거가 아니다.

Docker 실기동에서 별도 운영 오류도 찾았다. 기존 histogram 환경변수의 `PERCENTILES_HISTOGRAM`은 다른 속성으로 해석되어 기동이 실패했다. **`PERCENTILESHISTOGRAM`**으로 고친 후 실제 `http_server_requests_seconds_bucket`을 확인했다. Infra 기본 ConfigMap과 생성 스크립트도 함께 바꿔야 한다. 이전 smoke의 Prometheus 403 기대값도 현재 내부 scrape 허용 정책에 맞게 수정했으며, 공개 ALB 차단 검증은 Infra에 남겨둔다.

## 재현 순서

Java 21을 선택한 뒤 BE 루트에서 실행한다.

```sh
./gradlew test bootJar
docker compose -p orderpage-readiness -f compose.yaml -f compose.verify.yaml build --pull app
docker compose -p orderpage-readiness -f compose.yaml -f compose.verify.yaml up -d app
node scripts/container-smoke.mjs
node scripts/security-smoke.mjs
node scripts/lifecycle-smoke.mjs
```

검증 스크립트는 localhost:18080과 검증 전용 Compose 이름으로 제한한다. 실서비스 URL을 받지 않는다. 기존 검증 DB의 행을 유지한 상태에서 새 fixture를 추가했다. lifecycle 검사는 해당 검증 DB 중지·복구와 앱 종료를 수행하며 마지막에 앱을 멈춘다. 개발 프로젝트의 볼륨을 삭제하지 않는다.

AWS 자원·Vercel 배포·유료 스캐너는 사용하지 않았다. 실제 AWS TLS·IRSA·EKS/ALB 차단과 부하 성능은 배포 단계에서 검증해야 한다. Infra의 Vercel CLI 보안 잔여 항목도 별도로 남아 있다.

공식 근거: [Boot 요구사항](https://docs.spring.io/spring-boot/system-requirements.html), [Boot 4 전환 안내](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide), [Jackson 지원](https://docs.spring.io/spring-boot/reference/features/json.html), [JJWT](https://github.com/jwtk/jjwt), [RabbitMQ 릴리스](https://github.com/rabbitmq/rabbitmq-java-client/releases/tag/v5.37.1), [MariaDB 3.5.11](https://mariadb.com/docs/release-notes/connectors/java/3.5/3.5.11).
