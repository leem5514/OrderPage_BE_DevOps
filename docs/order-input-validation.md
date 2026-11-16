# 주문 입력 검증 보강

2026-11-16 KST. 배포 전 보안 점검에서 재현된 주문 수량 검증 누락을 수정했다. Spring/비밀번호 의존성과 배포 CLI 보안 문제는 별도 작업이며, 이 수정만으로 공개 배포 준비가 끝난 것은 아니다.

## 문제와 변경 후 동작

기존 Controller는 `@RequestBody List<@Valid OrderSaveReqDto>`를 받았지만, 현재 MVC 경로에서 목록 항목의 `@Positive`가 실행되지 않았다. 서비스도 전체 입력을 검증하지 않아 음수 수량이 재고 처리에 전달될 수 있었다. 단순한 JSON 형식 오류도 공통 예외 처리에서 500으로 응답해 장애 지표를 왜곡했다.

이제 `OrderingService.orderCreate`가 **전체 목록을 먼저 검사**한다. 하나라도 잘못됐다면 회원 조회·주문 저장·재고 처리·MQ 완료 처리·알림 호출 전에 거부한다. 뒤 상품이 잘못됐는데 앞 상품의 재고부터 감소시키는 흐름을 막기 위한 순서다.

| 입력 | 응답 / 처리 |
|---|---|
| 비어 있는 목록, null 항목 | HTTP 400, 업무 의존성 호출 없음 |
| 상품 ID 누락/null/0/음수 | HTTP 400, 업무 의존성 호출 없음 |
| 수량 누락/null/0/음수 | HTTP 400, 업무 의존성 호출 없음 |
| 정상 항목 뒤에 잘못된 항목 | 첫 항목을 처리하기 전에 목록 전체 거부 |
| 빈 body, JSON null, 깨진 JSON, 배열 대신 객체, int 범위 초과 | HTTP 400, 고정 오류 문구 |
| 정상 상품 목록 | 기존 HTTP 201, 주문 ID 반환, 재고 처리·저장·알림 유지 |
| 인증 없이 주문 요청 | 기존 HTTP 403 유지 |

서비스의 `@Transactional` 자체를 바꾸지는 않았다. 위 “호출 없음”은 테스트에서 Repository·StockProcessor·알림 의존성을 호출하지 않았다는 뜻이며, 트랜잭션 프록시의 연결 획득까지 검사한 결과는 아니다.

## 수정한 코드와 선택 이유

- [OrderingService](../src/main/java/com/example/ordersystem/ordering/service/OrderingService.java): null/빈 목록과 각 항목의 양수 ID·수량을 조회·정렬 전에 검증한다. 공통 진입점이므로 RDB_SYNC와 REDIS_ASYNC 모두 같은 규칙을 적용한다.
- [OrderController](../src/main/java/com/example/ordersystem/ordering/controller/OrderController.java): 실제로 검증을 보장하지 않던 목록 항목의 `@Valid`와 사용하지 않는 import를 제거했다. 기존 JSON 배열 계약은 유지한다.
- [CommonExceptionHandler](../src/main/java/com/example/ordersystem/common/service/CommonExceptionHandler.java): `HttpMessageNotReadableException`을 별도 400으로 처리한다. 파서 예외 원문·요청값은 응답이나 로그에 넣지 않는다. 공통 처리이므로 다른 JSON API의 같은 형식 오류에도 적용된다.

DTO 래퍼로 요청 형식을 바꾸거나 프론트를 수정할 필요 없이 Service 진입점을 보호하는 최소 변경을 선택했다. 이 검사는 주문 생성에만 적용하며, 주문 취소에서 사용하는 내부 재고 복원 이벤트의 음수 값에는 적용하지 않는다. 성능 최적화, 스키마·리소스·권한 변경은 포함하지 않는다.

## 검증

수정 전 새 주문 검사와 기존 서비스 검사를 실행했을 때 27건 중 24건이 실패했다. 잘못된 요청의 응답과 부작용 차단이 기존 코드에서 충족되지 않는 것을 확인한 뒤 수정했다.

수정 후 Java 11에서 `gradlew test` **50건 통과, 실패/오류/건너뜀 0건**을 확인했다.

| 검사 | 확인하는 경계 |
|---|---|
| [OrderInputValidationTest](../src/test/java/com/example/ordersystem/ordering/controller/OrderInputValidationTest.java), 18건 | 실제 Service를 연결한 MockMvc. 잘못된 입력/본문 17개, 정상 다중 상품 주문 1개. 응답과 업무 의존성 미호출 확인 |
| [OrderingServiceTest](../src/test/java/com/example/ordersystem/ordering/service/OrderingServiceTest.java), 9건 | 직접 서비스 호출의 잘못된 입력 7개와 기존 정상 저장·실패 보상 2개 |
| [SecurityConfigsTest](../src/test/java/com/example/ordersystem/common/configs/SecurityConfigsTest.java), 7건 | 기존 인증·상태 검사와 주문의 익명 접근 차단 |
| [ErrorPrivacyTest](../src/test/java/com/example/ordersystem/common/service/ErrorPrivacyTest.java), 2건 | 파서 예외의 합성 민감 입력이 응답·로그에 포함되지 않음 |
| 나머지 기존 검사, 14건 | JWT, Redis 설정, 두 재고 처리 모드, SSE 종료, 앱 context 등 |

BE 루트에서 실행한다. Java 11과 기존 Gradle 의존성 캐시가 있다면 네트워크 없이 재현할 수 있다.

```powershell
.\gradlew.bat --offline --no-daemon --console=plain test
```

MockMvc 경계 검사는 인증된 사용자 context를 설정하고 업무 의존성을 mock으로 대체한다. 실제 인증 필터는 별도 Spring context 검사에서 확인한다. 실제 MariaDB·Redis·MQ·AWS에 주문이나 부하를 보내지 않았으므로 운영 연동과 성능 측정을 대신하지 않는다.

## 다음 작업

이 변경을 검토·병합한 뒤 지원되는 Java/Spring Boot 계열로 보안 기반을 갱신하고, 긴 비밀번호 검증 문제와 실행 의존성을 다시 확인한다. Vercel CLI 잔여 권고 검토도 계속 필요하다.
