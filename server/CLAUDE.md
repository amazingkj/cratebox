# server — Spring Boot 원장·정산 엔진

Java 21 + Spring Boot 3.5 + **Spring Data JDBC/JdbcClient (JPA 아님)** + Flyway.
설계 근거는 `../docs/DATA-MODEL.md`. 여기는 코드 위치와 함정만 적는다.

## 패키지 (io.cratebox)

| 패키지 | 내용 |
|---|---|
| `inventory` | **StockDocService = 전기 매트릭스의 심장.** 문서 검증(validate)→전기(post)→역분개(reverse). DocType 12종, 위탁 이중 전기(settleOwner) |
| `settlement` | ClosingService(마감: 도장→MG 회수→정산서 생성 순서), PaymentService, TradeAgreementController(앨범 단위 계약), AdvanceController(MG) |
| `portal` | 기획사 포털 읽기전용 API (/api/portal/*, party_id 스코프) |
| `auth` | 세션 로그인, role ADMIN\|LABEL. SecurityConfig: /api/portal/** → LABEL, /api/** → ADMIN |
| `catalog`, `party`, `report` | 마스터·조회 |
| `common` | Vat(라인별 절사), JdbcUtils, 예외 |

## 규약·함정

- 마이그레이션 V1~V3 적용 완료. 스키마 변경은 **V4부터 추가** (기존 파일 수정 금지)
- `JdbcClient.single()`은 null 결과를 거부(TypeMismatchDataAccessException) — null 검증은 count 쿼리로
- PG JDBC는 timestamptz→Instant 직접 변환 불가 — OffsetDateTime 경유 (JdbcUtils)
- 기획사몫 = trunc(|공급가액| × (1−수수료율)), 부호는 나중에 복원(절사 나머지는 유통사 몫). 위탁 label 엔트리는 unit_price NULL
- 역분개는 엔트리 부호 반전 복제(제네릭) — 새 엔트리 타입을 추가해도 reverse 로직은 건드리지 않는 것이 원칙

## 실행·테스트

```bash
./gradlew bootRun    # 8087, DB는 docker compose(5433) 먼저
./gradlew test       # IT 2개는 실제 API 호출로 손계산 대조. 신규 시나리오는 별도 org 생성해 마감 충돌 회피
```

테스트 org 만들 때 BCrypt는 주입받은 PasswordEncoder 사용 (ConsignmentScenarioIT @BeforeAll 참고).
