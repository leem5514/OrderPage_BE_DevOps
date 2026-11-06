# Docker 실기동 검증 — 2026-11-06

검증 대상 앱 코드: `39bd52c0ecf3d92bb03c16a5a792047249593159`.
Docker Engine 29.7.2 / Compose v5.5.1, Linux x86_64, Docker 메모리 약 1.425 GiB.
API smoke 완료 시각: 2026-11-06T12:12:09.952Z (한국 시간 21:12:09).

## 기존 환경과 격리

사용자가 켠 `order-be-deploy`는 바탕화면의 기존 compose.yaml과 2026-07-30 생성 이미지였다.
기존 앱의 `/actuator/health`는 403으로, 새 코드 검증에 사용할 이미지가 아니었다.

메모리 중복 점유를 피하려고 원래 실행 중이던 네 컨테이너를 잠시 stop하고, 검증 후 같은 네 컨테이너를 다시 start했다. 기존 컨테이너/DB 볼륨/이미지를 삭제하거나 덮어쓰지 않았다.

- 검증 project: `orderpage-readiness`
- 검증 DB volume: `orderpage-readiness_mariadb_data`
- 검증 API: `127.0.0.1:18080` (검증 중에만 실행)
- DB/Redis/MQ 호스트 포트: 없음. 프로젝트 내부 네트워크만 사용.
- AWS 고정 키/세션 토큰은 빈 값, metadata 조회 비활성. 실제 AWS API를 호출하지 않았다.

## 빌드와 기동

원래 Dockerfile의 Java 11 JDK stage에서 Gradle bootJar 성공 후 Java 11 JRE stage로 기동했다.

| 항목 | 확인 결과 |
|---|---|
| 앱 image ID | sha256:7405283bf35accb8f2a98c8abed3d311563d355bb94eff626b1589ce64cb8358 |
| Java | Temurin 11.0.32.1+1 |
| MariaDB | 11.8.8 |
| Redis | 7.4.10 |
| RabbitMQ | 3.13.7 |
| 컨테이너 상태 | 데이터 서비스 3개 healthy, Spring Boot 기동 완료 |
| 앱 OOM / 재시작 | OOMKilled=false, RestartCount=0 (검증 종료 직전) |

기능 확인 후 `compose stop`에서는 SIGTERM(15) 뒤 SIGKILL(9)이 기록됐고 ExitCode=137이었다. 종료 후에도 OOMKilled=false였다. 따라서 정상적인 graceful shutdown을 검증했다고 볼 수 없다. 종료 유예시간, SSE 연결과 Spring 종료 처리, Kubernetes terminationGracePeriodSeconds를 롤링 배포 준비 단계에서 맞추고 다시 검증해야 한다.

Dockerfile은 bootJar만 수행한다. 이번 Docker 빌드가 JUnit 20개를 다시 실행했다는 뜻은 아니다. 이전 JDK 17 테스트 20개와 이번 Java 11 실기동/HTTP 검증을 구분한다.

## 통합 확인 13개 — 모두 통과

1. 익명 `/actuator/health` → 200 / UP.
2. 공개 상품 목록 → 200.
3. 새 DB에 이전 기본 관리자 `admin@test.com`이 없음.
4. 공개 가입 body에 ADMIN을 넣어도 DB에는 USER로 저장.
5. 로그인 성공, 발급 refresh token과 Redis DB 1 저장값 일치.
6. JWT로 본인 회원 정보 조회 → 200.
7. USER로 전체 회원 목록 접근 → 403.
8. 정상 refresh token으로 access token 발급 성공.
9. 잘못된 refresh token → 401.
10. 익명 Prometheus endpoint → 403.
11. 허용된 로컬 origin의 CORS preflight 성공.
12. SSE 구독이 connect/connected 이벤트 수신.
13. 테스트 상품 재고 10에서 수량 2 주문 → 201, Redis DB 2와 RabbitMQ consumer가 반영한 MariaDB 재고 모두 8, 본인 주문 목록 확인.

상품 등록/S3 업로드와 분리하기 위해 검증 DB에 상품 fixture 한 건을 직접 삽입했다. 스크립트는 실행마다 테스트 회원/상품/주문을 소량 추가하며 자동 삭제하지 않는다. 테스트 자격증명과 JWT는 출력하지 않는다.

## 재현 명령

저장소 루트에서 실행한다. 현재 머신은 메모리가 작으므로 다른 프로젝트의 실행 부하를 먼저 확인한다.

```powershell
docker compose -p orderpage-readiness -f compose.yaml -f compose.verify.yaml config --quiet
docker compose -p orderpage-readiness -f compose.yaml -f compose.verify.yaml build app
docker compose -p orderpage-readiness -f compose.yaml -f compose.verify.yaml up -d --no-build
# 앱 기동 및 /actuator/health UP을 확인한 후
node scripts/container-smoke.mjs
docker compose -p orderpage-readiness -f compose.yaml -f compose.verify.yaml stop
```

스크립트는 프로젝트 label과 127.0.0.1:18080 바인딩을 확인한 뒤 검증 DB만 수정한다. 다른 프로젝트명/서버 URL을 받지 않는다. Node 18+와 Docker CLI가 필요하며 실제 검증은 Node 20.16.0에서 수행했다. override 태그는 사용 중인 Compose v5.5.1에서 검증했다.

## 남은 범위

- AWS IRSA/STS, RDS JDBC TLS, ElastiCache TLS·AUTH, Amazon MQ AMQPS의 실제 연결.
- 실제 S3 상품 이미지 업로드와 브라우저 읽기 권한.
- 관리자 bootstrap의 컨테이너 실기동 (기존 단위 테스트만 완료), 주문 취소.
- SSE 주문 이벤트 전체 전달/재연결, 다중 Pod, 롤링 배포와 HPA.
- Vercel/운영 도메인의 실제 브라우저 CORS.
- 부하·장애·중복 메시지 상황의 정합성/성능 실험.

이 결과는 로컬 기능 smoke이며 성능 Before/After 수치나 AWS 배포 완료 증거가 아니다.

## 종료 상태

검증 컨테이너 네 개는 중지했고 검증 DB 볼륨은 보존했다. 원래 `order-be-deploy` 네 개를 다시 실행했다. 따라서 현재 8080은 이전 코드이며, 새 이미지가 원래 서비스로 교체된 것은 아니다.

복구 후 기존 앱 image ID가 변경되지 않았고 `/product/list`가 HTTP 200으로 응답함을 확인했다.
