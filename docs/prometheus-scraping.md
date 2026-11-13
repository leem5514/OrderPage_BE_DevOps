# Prometheus 수집 경로 준비

기준일: 2026-11-13. 이 변경은 인프라 레포지토리의 kube-prometheus-stack ServiceMonitor가 백엔드 지표를 읽기 위한 최소 운영 수정이다. 주문·재고·인증 기능 로직은 변경하지 않는다. EKS 설치와 실제 지표 수집은 아직 하지 않았다.

## 변경 이유와 경계

- 기존 `spring-boot-starter-actuator`, `micrometer-registry-prometheus`, `application.yml`의 `health,info,metrics,prometheus` 노출 설정을 그대로 사용한다.
- `SecurityConfigs.java`에서 `/actuator/prometheus`만 익명 조회 허용한다. ServiceMonitor가 내부 `orderpage-backend` Service의 `http` 포트로 접근하므로 별도 API token을 넣지 않는다.
- 공개 ALB Ingress는 `/actuator` 선순위 경로를 고정 404로 처리한다. 배포 후 외부 `/actuator/prometheus`가 404인지 확인해야 한다. 클러스터 내부 다른 workload는 이 지표를 볼 수 있으므로 요청자 식별값이나 비밀값을 metric label에 넣지 않는다.
- `/actuator/metrics`와 다른 관리 경로는 익명 접근을 허용하지 않는다.
- p95 측정에 필요한 HTTP histogram은 인프라 ConfigMap 환경 변수에서 활성화한다. 모든 비교군에 동일하게 적용한다.

## 로컬 검증과 실제 배포 시 확인

`SecurityConfigsTest`의 Spring test context는 기본 설정에서 Prometheus exporter를 비활성화한다. 테스트 속성 `management.metrics.export.prometheus.enabled=true`를 명시해 `/actuator/prometheus` 200과 `/actuator/metrics` 403을 함께 확인했다. 로컬 Gradle `SecurityConfigsTest` 6개 통과. H2 종료 시 출력되는 이미 닫힌 DB 경고는 테스트 종료 단계에서 발생했다.

실제 EKS에서는 새 백엔드 이미지 배포 후 내부 Service 또는 Pod에서 `/actuator/prometheus` 200과 `http_server_requests_seconds_bucket` 존재를 확인한다. 같은 시점에 공개 도메인의 `/actuator/prometheus` 404, Prometheus Target UP, 올바른 application/namespace/pod label을 확인한다. 실제 수집 전에는 Grafana 빈 패널을 성능 0으로 발표하지 않는다.

상세 chart 설치·측정 절차는 Infra 레포지토리의 `docs/restart/prometheus-grafana.md`에 기록한다.
