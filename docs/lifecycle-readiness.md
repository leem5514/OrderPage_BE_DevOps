# 종료 처리와 상태 점검 분리 — 2026-11-06

이전 앱 기준: 7eca533. 이번 작업은 재고/조회 성능 변경 없이 배포 생명주기만 보완한다.
검증 이미지: `sha256:9206f11696678ca755cd077579094f7f94c33ef1859a05aeb1da48230bdced6d`.

## 왜 변경했는가

이전 Docker 실기동에서 기능 13개는 통과했지만 stop 시 SIGTERM 뒤 SIGKILL, 종료 코드 137이 관측됐다. OOM은 아니었다. 종료 시간을 충분히 주고 HTTP/SSE를 마무리하는 처리가 필요했다. 당시 어떤 종료 단계가 가장 오래 걸렸는지까지 확정한 것은 아니다.

또한 기존 Kubernetes liveness/readiness가 같은 전체 health 경로를 사용했다. DB 장애를 앱 사망으로 판단하면 모든 Pod를 재시작하는 상황이 생길 수 있어 두 판단을 분리했다.

## 설정의 의미

| 위치/설정 | 값 | 역할 |
|---|---|---|
| server.shutdown | graceful | SIGTERM 이후 신규 HTTP 수락을 중단하고 진행 중 요청을 마무리 |
| spring.lifecycle.timeout-per-shutdown-phase | 20s | Spring 종료 단계별 대기 제한. 전체 프로세스 20s 제한이 아님 |
| Compose stop_grace_period | 60s | Docker가 SIGTERM 뒤 SIGKILL을 보내기까지의 예산 |
| health.probes.enabled | true | 로컬에서도 readiness/liveness 경로를 제공 |
| liveness 그룹 | livenessState | 앱 자체 상태만 검사. DB/Redis/MQ를 포함하지 않음 |
| readiness 그룹 | readinessState,db | 앱 준비 상태와 DB 연결을 함께 검사 |
| 전체 health | 기존 구성 유지 | DB/Redis/MQ를 포함한 종합 운영 상태 관측 |

각 경로는 일반 API와 같은 8080 포트를 사용하며 기존 SecurityConfig의 health 허용 규칙으로 익명 검사 가능하다. 지표 endpoint의 익명 403은 유지한다.

공유 DB가 모든 Pod에서 불능이면 모든 readiness가 실패할 수 있다. 주요 API에 DB가 필수인 현재 서비스의 선택이다. Redis/MQ까지 readiness에 추가하지 않았으므로 readiness=UP이 모든 업무 기능 정상임을 보장하지 않는다. 캐시/메시지 계층은 전체 health와 별도 운영 알림으로 관측한다. livenessState 역시 모든 deadlock을 탐지하는 지표는 아니다.

## SSE 종료 처리

SSE는 장시간 열린 HTTP 요청이다. 단순히 graceful shutdown만 켜면 연결이 끝나기를 기다리다가 시간 제한에 도달할 수 있다.

SseController가 ContextClosedEvent를 받으면 신규 구독을 503으로 거절하고, 추적 중인 SseEmitter를 complete한다. 구독 등록과 종료 정리를 같은 lock으로 보호해 종료 정리 직후 새 연결이 남지 않게 했다. 동일 사용자 재구독 시 이전 연결도 끝내며, 이전 callback이 새 연결을 제거하지 않도록 remove(email, emitter)를 사용한다.

서버가 SSE를 정상 EOF로 끝내는 것과 클라이언트의 자동 재연결/이벤트 재전달은 별개다. 현재 FE의 onerror close 동작과 이벤트 유실·재연결은 후속 배포 실험 전에 검증해야 한다. 이번 결과로 SSE 무중단을 주장하지 않는다.

## 검증 결과

JDK 17의 단위/통합 테스트 22개 및 bootJar 통과. Java 11/Linux Docker 빌드 성공. 기존 HTTP/Redis/MQ/DB 기능 smoke 13개도 새 이미지에서 재통과했다.

실제 상태 검증은 격리 프로젝트 orderpage-readiness에서 수행했다. 기본 DB 대신 이 프로젝트의 MariaDB 컨테이너만 stop/start했다.

| 상황 | 관측 |
|---|---|
| DB 정상 | readiness 200 |
| DB 중지 | liveness 200 / UP, readiness 503 / DOWN |
| DB 복구 | readiness 200, 앱 RestartCount 변화 없음 |
| SSE 연결을 연 채 앱 중지 | 1.502초, ExitCode 143, SIGTERM(15)만 기록, SSE 정상 EOF |
| 유휴 앱 중지 | 1.171초, ExitCode 143, SIGTERM(15)만 기록 |
| 두 종료 모두 | OOMKilled=false, HTTP graceful 완료 및 DB pool 종료 로그 확인 |

측정 시각: 2026-11-06T12:28:04.089Z. 각 종료 시간은 docker stop 호출부터 반환까지의 로컬 단일 측정이다. 이전 종료 시간과 동일 조건 반복 비교를 하지 않았으므로 시간 개선율을 계산하지 않는다. 143은 SIGTERM 종료 코드이며 이 실험에서는 정리 완료 로그/EOF/SIGKILL 없음과 함께 판정했다.

이전의 137/SIGKILL → 이번의 143/SIGTERM은 종료 동작 수정의 증거다. 부하 중 모든 요청 보존, AWS/ALB 또는 Kubernetes 롤링 배포 성공의 증거는 아니다.

## 재현

기존 개발 환경과 포트를 공유하지 않는다. Docker 메모리가 작은 PC는 다른 프로젝트를 잠시 중지한 뒤 실행하고, 검증 후 원래 상태를 복원한다.

```powershell
docker compose -p orderpage-readiness -f compose.yaml -f compose.verify.yaml build app
docker compose -p orderpage-readiness -f compose.yaml -f compose.verify.yaml up -d --no-build
node scripts/lifecycle-smoke.mjs
# lifecycle smoke는 앱을 중지한 상태로 끝난다. 나머지 검증 서비스도 중지한다.
docker compose -p orderpage-readiness -f compose.yaml -f compose.verify.yaml stop
```

스크립트는 고정된 프로젝트 label/loopback 포트/StopTimeout을 확인한다. DB 중지 실험은 finally에서 DB를 다시 시작한다. 테스트 회원은 검증 DB에만 추가하고 자격증명을 출력하지 않는다. 단순 성능 도구가 아니라 장애 주입 도구이므로 원격 대상 URL을 받지 않는다.

이번 실행 후 새 검증 컨테이너는 중지했고, 원래 order-be-deploy 네 컨테이너와 기존 DB를 복구했다. 기존 8080 서비스가 이번 이미지로 교체된 것은 아니다.

## Kubernetes와 연결할 조건

- startupProbe: liveness 경로, 5초 간격 x 24회 실패. 기동 중 premature restart를 완화한다.
- readinessProbe: readiness 경로, 실패 시 Service 대상에서 제외.
- livenessProbe: liveness 경로, 외부 DB 장애만으로 재시작하지 않도록 구성.
- terminationGracePeriodSeconds=60. preStop의 sleep 5초도 이 시간에 포함된다.
- ALB healthcheck 경로도 readiness로 통일한다.

20초는 단계별 제한이므로 여러 종료 단계가 오래 걸리면 60초를 초과할 수 있다. 현재 값은 로컬 검증을 통과한 초기값이며 부하/SSE/MQ 조건에서 재확인한다. preStop 5초가 ALB 라우팅 제외 전파에 충분한지, ALB deregistration delay와 어떤 관계인지도 EKS 실측으로 정한다.

## 공식 근거

- [Spring Boot 2.7.11 graceful shutdown](https://docs.spring.io/spring-boot/docs/2.7.11/reference/html/web.html#web.graceful-shutdown)
- [Spring Boot 2.7.11 Kubernetes probes](https://docs.spring.io/spring-boot/docs/2.7.11/reference/html/actuator.html#actuator.endpoints.kubernetes-probes)
- [Kubernetes Pod 종료 절차](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#pod-termination)
