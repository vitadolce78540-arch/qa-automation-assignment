# 작업 방식 및 AI 활용

이 과제는 요구사항 분석과 테스트 설계에 가장 많은 시간을 쓰고, 코드 작성은 AI 도구(Anthropic **Claude**)를 활용했습니다.

| 구분 | 담당 | 내용 |
| --- | --- | --- |
| 요구사항 분석 | 본인 | 재고 처리 규칙 해석(선착순, 운영자 수정 중 주문 처리 순서, 장바구니 시점 미차감, 품절 즉시 반영), 명세에 없는 부분의 가정 정의(멱등성 키, 운영자 API, 오류 코드) |
| 테스트 설계 | 본인 | 테스트 케이스 도출과 우선순위, 동시성 시나리오(송신 간격·반복 라운드) 설계, 결함 검출 검증 방법(결함 모드) 정의 |
| Mock 서버 | 본인 + Claude | 기본 WireMock 서버 셋팅은 직접 진행, 상태를 가진 응답 변환 확장(`StockStubTransformer`, `StockStore`)은 Claude 로 작성 |
| 테스트 코드 | Claude | 설계한 케이스를 바탕으로 JUnit 5·RestAssured 테스트, 공통 확장·헬퍼 코드 작성 |
| 검토·검증 | 본인 | 생성된 코드 리뷰, 직접 실행해 정상 모드 전체 통과와 결함 모드 동시성 테스트 실패를 확인 |
| 문서 | 본인 + Claude | README 초안은 Claude 로 작성하고 내용 검토·수정 |

AI 가 작성한 코드도 제출자의 책임으로 보고, 모든 테스트의 의도와 검증 기준을 직접 설명할 수 있도록 검토했습니다.

# B마트 재고 API 테스트 자동화 (JUnit 5 · RestAssured · WireMock)

B마트 재고 처리 규칙(조회·차감·복구·품절·운영자 수정)과 피크 타임 동시 주문 정합성을 검증하는 API 테스트입니다.
실제 서버가 없으므로 **WireMock 기반 스텁 서버**를 함께 제공하며, 같은 테스트를 실행 옵션 하나로 실제 QA/STG 서버에 붙일 수 있습니다.

- 테스트 케이스 21개 (파라미터 포함 29건 실행)
- 재고 1개에 두 결제가 0·10·30·50ms 간격으로 도달하는 동시성 테스트, 간격마다 10라운드 반복
- 스텁의 "Lock 없는 결함 모드"로 동시성 테스트가 초과 판매를 실제로 잡아내는지 검증

| 구분 | 사용 기술 |
| --- | --- |
| 언어 / 빌드 | Java 17+, Gradle (Wrapper 포함) |
| 테스트 프레임워크 | JUnit 5 (Jupiter, Parameterized) |
| API 호출 | RestAssured 5 |
| Mock 서버 | WireMock 3 (standalone) + 응답 변환 확장 |

## 1. 실행 방법

```bash
./gradlew test                       # WireMock 스텁을 자동으로 띄우고 전체 실행
./gradlew concurrencyTest            # 동시성 테스트(@Tag("concurrency"))만
./gradlew test -Pstub.unsafe=true    # 결함 모드 스텁으로 실행 → 동시성 테스트가 실패해야 정상
./gradlew test -Pbmart.baseUrl=https://qa-api.example.com   # 실서버(QA/STG) 대상 실행
./gradlew runStub -Pport=8080        # 스텁 서버만 단독 실행 (curl·Postman 확인용)
```

| 옵션 | 기본값 | 설명 |
| --- | --- | --- |
| `-Pbmart.baseUrl` (또는 환경변수 `BMART_BASE_URL`) | 없음 | 지정하면 스텁 대신 해당 서버에 테스트 실행 |
| `-Pstub.delayMs` | 30 | 스텁의 결제 승인 지연(ms). 경쟁 구간을 넓혀 동시성 결함이 드러나게 함 |
| `-Pstub.unsafe` | false | `true` 면 Lock 없이 재고를 처리하는 결함 버전 스텁으로 실행 |

Windows 에서는 `./gradlew` 대신 `gradlew.bat` 을 사용합니다.

```bat
gradlew.bat test
gradlew.bat concurrencyTest
gradlew.bat test -Pstub.unsafe=true
```

JDK 17 이상이 필요합니다. Gradle Wrapper 9.1.0 을 사용하므로 JDK 25 에서도 실행됩니다. Gradle 과 의존성은 첫 실행 시 자동으로 내려받습니다.

## 2. 프로젝트 구조

```
src/test/java/bmart/stock/
├── stub/                            # WireMock 스텁 서버
│   ├── StockStubServer.java         # WireMockServer 설정 + 엔드포인트별 stubFor 매핑
│   ├── StockStubTransformer.java    # 응답 변환 확장: 요청을 StockStore 로 넘겨 응답 생성
│   ├── StockStore.java              # 재고 규칙 구현 (상품별 Lock, 결제 지연, 결함 모드)
│   └── StockStubMain.java           # 스텁 단독 실행
├── support/                         # 테스트 공통 코드
│   ├── BmartApiExtension.java       # JUnit 5 확장: 서버 수명주기 + StockApi/TestData 주입
│   ├── StockApi.java                # RestAssured 기반 API 클라이언트
│   ├── TestData.java                # 테스트 데이터 준비 (고유 상품·고객 생성)
│   ├── StockAssertions.java         # 검증 헬퍼
│   └── Concurrency.java             # 동시 요청 실행기 (CyclicBarrier + 송신 간격 제어)
├── StockAndOrderTest.java           # 조회 · 장바구니 · 결제 차감 · 입력 검증 · 멱등성
├── CancelAndSoldOutTest.java        # 품절 전환 · 다른 탭 반영 · 취소 복구 · 운영자 수정
└── ConcurrencyTest.java             # 동시 결제 · 피크 타임 · 운영자 수정 vs 주문
```

## 3. API 명세 (가정)

공통: JSON 요청/응답, 오류는 `{"code": "...", ...}` 형태.

### GET /v1/products/{productId}/stock — 재고 조회

```json
200 { "productId": "p1", "quantity": 3, "status": "AVAILABLE", "orderable": true, "version": 7 }
404 { "code": "PRODUCT_NOT_FOUND" }
```

- `status`: `AVAILABLE` | `SOLD_OUT` — 고객 화면의 "품절" 배지
- `orderable`: 주문 버튼 활성화 여부 (`quantity == 0` 이면 `false`)
- `version`: 재고가 바뀔 때마다 1씩 증가. 다른 탭이 변경을 감지하는 기준

### POST /v1/orders — 주문 생성 (결제 완료 → 재고 즉시 차감)

요청 헤더 `Idempotency-Key`(선택), 본문 `{ "customerId": "c1", "productId": "p1", "quantity": 1 }`

| 응답 | 조건 |
| --- | --- |
| `201` `{orderId, status: "PAID", customerId, productId, quantity, remainingStock}` | 차감 성공 |
| `200` (기존 주문 + `"replayed": true`) | 같은 고객이 같은 `Idempotency-Key` 로 재요청 |
| `400 INVALID_REQUEST` | 수량이 1 이상의 정수가 아님, 필수값 누락 |
| `404 PRODUCT_NOT_FOUND` | 없는 상품 |
| `409 SOLD_OUT` | 재고 0 |
| `409 INSUFFICIENT_STOCK` `{availableQuantity}` | 0 < 재고 < 주문 수량 |

### POST /v1/orders/{orderId}/cancel — 주문 취소 (재고 즉시 복구)

| 응답 | 조건 |
| --- | --- |
| `200` `{orderId, status: "CANCELLED", restoredQuantity, currentStock}` | 복구 성공 |
| `404 ORDER_NOT_FOUND` | 없는 주문 |
| `409 ALREADY_CANCELLED` | 이미 취소됨 (이중 복구 방지) |

### PUT /v1/admin/products/{productId}/stock — 운영자 재고 수동 수정 (추가 정의)

본문 `{ "quantity": 10, "operatorId": "op1" }` → `200` (조회와 같은 형태), 음수면 `400`.
테스트 데이터 준비에도 이 API 를 사용합니다.

### 처리 규칙 해석

- **선착순:** 같은 상품의 재고 변경(주문·취소·운영자 수정)은 상품 단위로 직렬화해 서버 도착 순서대로 처리합니다. 재고가 모자라면 이후 요청은 `SOLD_OUT` 또는 `INSUFFICIENT_STOCK` 으로 거절합니다.
- **운영자 수정 중 주문 발생:** 도착 순서대로 처리하고, 운영자 입력값은 "현재 실물 재고"로 보고 절대값으로 덮어씁니다. 운영자 수정이 먼저면 수정값에서 주문이 차감되고, 주문이 먼저면 차감 후 운영자 값으로 덮어씁니다.
- **장바구니:** 담는 시점에는 조회만 일어나므로 재고·`version` 이 바뀌지 않아야 합니다.

## 4. 테스트 케이스

| ID | 클래스 | 분류 | 검증 내용 |
| --- | --- | --- | --- |
| TC01 | StockAndOrderTest | 정상 | 재고 조회 응답 필드와 값 |
| TC02 | StockAndOrderTest | 예외 | 없는 상품 조회 → 404 |
| TC03 | StockAndOrderTest | 정상 | 장바구니 담기(반복 조회)로는 재고·version 변동 없음 |
| TC04 | StockAndOrderTest | 정상 | 결제 완료 시 주문 수량만큼 즉시 차감 |
| TC05 | StockAndOrderTest | 예외 | 재고보다 많이 주문 → 409 `INSUFFICIENT_STOCK`, 재고 유지 |
| TC06 | StockAndOrderTest | 예외 | 잘못된 수량(0, -1, "2", null, 1.5) → 400, 재고 유지 |
| TC07 | StockAndOrderTest | 예외 | 없는 상품 주문 → 404 |
| TC08 | StockAndOrderTest | 정상 | 같은 결제 재전송(Idempotency-Key) 시 1회만 차감 |
| TC09 | CancelAndSoldOutTest | 품절 | 마지막 재고 주문 → `SOLD_OUT`, 주문 버튼 비활성화 |
| TC10 | CancelAndSoldOutTest | 품절 | 품절 상품 주문 → 409 `SOLD_OUT` |
| TC11 | CancelAndSoldOutTest | 품절 | 다른 탭에서 조회 중인 고객에게도 품절 즉시 반영 |
| TC12 | CancelAndSoldOutTest | 복구 | 취소 시 차감된 재고 즉시 복구 |
| TC13 | CancelAndSoldOutTest | 복구 | 품절 상품 취소 → 즉시 다른 고객 구매 가능 |
| TC14 | CancelAndSoldOutTest | 복구 | 같은 주문 두 번 취소 → 409, 재고는 한 번만 복구 |
| TC15 | CancelAndSoldOutTest | 예외 | 없는 주문 취소 → 404 |
| TC16 | CancelAndSoldOutTest | 운영자 | 수동 수정 즉시 반영, 품절 해제 후 주문 가능 |
| TC17 | CancelAndSoldOutTest | 운영자 | 음수 재고 수정 → 400 |
| TC18 | ConcurrencyTest | 동시성 | **재고 1개에 A·B가 0/10/30/50ms 간격 결제 → 1건 성공, 1건 `SOLD_OUT`, 간격이 있으면 먼저 보낸 A가 성공** (간격별 10라운드) |
| TC19 | ConcurrencyTest | 동시성 | 재고 5개에 20명 동시 결제 → 정확히 5건 성공, 주문 ID 중복 없음, 재고 0 |
| TC20 | ConcurrencyTest | 동시성 | 재고 3개에 2개씩 동시 주문 → 1건 성공, 1건 `INSUFFICIENT_STOCK`(잔여 1) |
| TC21 | ConcurrencyTest | 동시성 | 운영자 수정과 주문이 겹칠 때 도착 순서대로 처리 (운영자 먼저 → 9, 주문 먼저 → 10) |

## 5. 테스트 코드 구조 설계

### 서버 수명주기와 Fixture 주입 (`BmartApiExtension`)

JUnit 5 `ParameterResolver` 확장 하나가 공통 fixture 역할을 합니다.

- **서버:** `bmart.baseUrl` 이 있으면 그 서버를 쓰고, 없으면 WireMock 스텁을 **전체 실행에서 한 번만** 띄웁니다. 루트 `ExtensionContext.Store` 에 `CloseableResource` 로 저장해 모든 테스트가 끝나면 JUnit 이 자동으로 내립니다.
- **파라미터 주입:** 테스트 메서드에 `StockApi`, `TestData` 를 선언하면 주입합니다. `StockApi` 를 두 개 선언하면 서로 다른 인스턴스가 들어와 "기본 탭 / 다른 탭에서 보고 있는 고객"을 표현합니다 (TC11, TC16).

```java
@Test
void tc11_soldOutVisibleInOtherTab(StockApi api, StockApi otherTab, TestData data) { ... }
```

### 데이터 준비 (`TestData`)

- **테스트마다 새 상품·새 고객:** `data.product(1)` 은 `qa-item-1a2b3c4d` 처럼 고유 ID 상품을 만들고 재고를 세팅합니다. 공유 데이터가 없어 실행 순서와 무관하고, JUnit 병렬 실행으로 바로 확장할 수 있으며, 실서버에서 다른 사람의 테스트와 섞여도 서로 영향이 없습니다.
- **준비는 운영자 API로:** DB 에 직접 넣지 않고 공개 API 로 상태를 만들기 때문에 스텁과 실서버에서 같은 코드가 동작합니다. 실서버에 별도의 테스트 데이터 API 가 있다면 `TestData` 만 교체합니다.

### API 클라이언트 (`StockApi`)

URL·헤더·본문 구성은 이 클래스에만 있고, 테스트는 `api.createOrder(customer, product, 1)` 처럼 업무 언어로 호출합니다. 명세가 바뀌면 이 파일만 고칩니다. 주문 수량을 `Object` 로 받아 `"2"`, `null`, `1.5` 같은 잘못된 타입도 그대로 보낼 수 있습니다.

### 검증 헬퍼 (`StockAssertions`)

- `assertStock(api, product, n)` 은 수량뿐 아니라 수량에서 파생되는 화면 상태(`status`, `orderable`)와 음수 재고 여부까지 한 번에 검증합니다. 품절 규칙이 모든 테스트에서 자동으로 함께 확인됩니다.
- 모든 헬퍼는 실패 시 `-> 상태코드 응답본문` 을 메시지에 남겨, 리포트만 보고 원인을 추적할 수 있습니다.

### 동시성 실행기 (`Concurrency`)

1. 요청 스레드를 `CyclicBarrier` 로 같은 출발선에 세웁니다. 배리어가 열리는 순간을 기준 시각으로 기록합니다.
2. 각 요청은 지정한 오프셋(ms)만큼 기다렸다가 보냅니다. "수십 ms 간격 도달"을 오프셋으로 재현합니다.
3. 실제 송신 시각을 결과에 남겨, 실패 메시지에 `[(A, 201, 0.1ms), (B, 201, 30.1ms)]` 처럼 누가 언제 보내 어떤 응답을 받았는지 보여줍니다.
4. 경쟁 상태는 매번 드러나지 않으므로 같은 시나리오를 라운드마다 새 상품으로 10번 반복합니다.

### WireMock 스텁 설계 (`stub/`)

WireMock 의 일반 stub 매핑은 "요청 → 고정 응답"이라, 재고처럼 요청마다 바뀌는 상태와 선착순 규칙을 표현할 수 없습니다. 그래서 역할을 나눴습니다.

- **WireMock:** HTTP 서버, 요청 매칭(`stubFor(get(urlPathMatching(...)))`), 동시 요청 처리(Jetty 스레드 64개)
- **응답 변환 확장(`StockStubTransformer`):** 매칭된 요청을 `StockStore` 로 넘겨 응답 본문을 만듦
- **`StockStore`:** 상품별 `ReentrantLock` 으로 재고 변경을 직렬화(선착순), 결제 승인 지연으로 경쟁 구간을 재현, 결함 모드 지원

## 6. 테스트가 실제로 결함을 잡는지 검증

결함 모드(`-Pstub.unsafe=true`)의 스텁은 Lock 없이 "재고 조회 → 결제 지연 → 저장"을 합니다. 동시 결제 시 초과 판매가 발생하는 전형적인 결함입니다.

```bash
./gradlew concurrencyTest -Pstub.unsafe=true
```

정상 모드에서는 전부 통과하고, 결함 모드에서는 동시성 테스트가 아래처럼 실패해야 정상입니다.

| 테스트 | 결함 모드 결과 | 의미 |
| --- | --- | --- |
| TC18 (0·10·30ms) | 실패: A·B 모두 201 | 재고 1개를 두 고객이 모두 결제 (초과 판매) |
| TC18 (50ms) | 통과 | 스텁의 경쟁 구간(결제 지연 30ms)보다 간격이 길어 두 요청이 겹치지 않음 |
| TC19 | 실패: 20건 모두 성공 | 재고 5개에 20명 결제 |
| TC20 | 실패: 두 건 모두 성공 | 재고 3개에 4개 판매 |
| TC21 (주문 먼저) | 실패 | Lock 이 없어 운영자 수정이 결제 도중 끼어듦 |

- 30ms 간격은 첫 라운드는 통과하고 이후 라운드에서 실패하는 경우가 있습니다. 한 번 통과로 안전하다고 판단하지 않고 라운드를 반복하는 이유입니다.
- 실제 서버의 처리 시간은 스텁과 다르므로, 실서버에서는 간격 목록을 실제 처리 시간에 맞게 조정합니다.

## 7. 실제 서버 적용과 확장

- **실서버 전환:** `-Pbmart.baseUrl=...` 로 서버 주소만 바꾸면 같은 테스트가 실행됩니다.
- **CI 통합:** API 테스트라 빠르게 끝나므로 머지 전 단계에서 매번 실행하고, 동시성 테스트(`concurrencyTest`)는 라운드를 늘려 야간 빌드에서 반복 실행합니다.
- **Flaky 관리:** 동시성 테스트가 간헐적으로 실패하면 테스트를 의심하기 전에 실제 경쟁 상태 결함인지 먼저 확인합니다. 실패 메시지의 송신 시각과 서버 로그의 처리 순서를 대조합니다.
- **실시간 반영:** "다른 탭 반영"은 재조회 결과와 `version` 으로 검증했습니다. 실제 서비스가 WebSocket·SSE 로 품절을 푸시한다면 푸시 수신 여부와 수신까지 걸린 시간을 검증하는 테스트를 추가해야 합니다.
- **스텁의 한계:** 스텁은 메모리 기반 단일 프로세스입니다. 실서버의 다중 인스턴스·DB 트랜잭션·캐시에서 생기는 결함은 실서버 대상 실행과 부하 테스트로 검증해야 합니다.

## 8. 작업 방식 및 AI 활용

원본 코드 분석과 시나리오·설계 방향은 직접 정하고, 코드 작성은 AI 도구(Anthropic Claude)를 활용했습니다.

| 영역 | 담당 | 내용 |
| --- | --- | --- |
| 문제점 분석 | 본인 + Claude | 안정성·유지보수성·신뢰성 관점 분석을 정리 |
| 시나리오 정의 | 본인 | 앱 실행 → 타임세일 팝업 닫기 → 음식배달 더보기 → 치킨(없으면 메뉴 전체보기) → 첫 가게 → 메뉴 → 주문 → 결제 화면 흐름 정의 |
| 설계 방향 | 본인 | Appium + Java 선택, 기존 보유 지식인 E2E 스크립트 구조(공통 Base 클래스 상속, 단계 로그, 진입 시간 측정) 설계 |
| 실기기 확인 | 본인 + Claude | 실기기를 연결해 패키지·액티비티와 홈 화면 요소를 확인하고 로케이터 설계에 반영 |
| 테스트 코드 | Claude | Page Object, 조건 기반 대기, 단계별 검증, 실패 아티팩트 저장 코드 작성 |
| 문서 | 본인 + Claude | README 초안은 Claude 로 작성하고 내용 검토·수정 |