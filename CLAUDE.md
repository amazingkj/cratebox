# cratebox

중소 기획사·인디 레이블·소규모 음반 유통사용 재고·정산 SaaS MVP.
사입(구매)과 위탁을 **하나의 append-only 이중 원장**으로 처리하는 것이 핵심 설계.

## 문서 (자세한 내용은 여기서 — 코드 전체를 다시 읽지 말 것)

- `docs/SPEC.md` — 기능 명세, 단계(① 사입 완료 / ② 위탁 완료 / ③ 음원 예정), 테스트 시나리오
- `docs/DATA-MODEL.md` — 원장 설계(테이블, 전기 매트릭스, 부호 규약, 마감/소급 규칙)
- `server/CLAUDE.md`, `web/CLAUDE.md` — 각 모듈 구조·규약

## 환경 (Windows 10, Docker)

- Postgres 16: **포트 5433** (docker compose up -d). ⚠️ 5432(postgres_db 컨테이너)와 8080은 다른 서비스가 사용 중 — 절대 건드리지 말 것
- 앱: **포트 8087** (`cd server && ./gradlew bootRun`), 로그인 admin / admin1234!
- Maven/Gradle CLI 없음 → 반드시 Gradle wrapper 사용. Git Bash에서 실행

## 핵심 설계 불변식 (변경 금지)

- 원장 2개: `inventory_entry`(수량) + `settlement_entry`(금액). append-only, DB 트리거가 UPDATE/DELETE 차단. 수정은 역분개(reverse)로만
- 문서(stock_doc, DRAFT) → 확정(post) 시 전기 매트릭스에 따라 엔트리 생성
- 두 직교 차원: `owner_party_id`(null=자사, 값=위탁 기획사 소유) / location(WAREHOUSE|거래처 매장)
- 부호: settlement 양수 = 받을 돈(채권). 입금 IN=−, 지급 OUT=+. 잔액 = SUM(amount)
- 마감 = 미배정 엔트리에 기간 도장(stamping). 마감후 반품은 차기 이월이 기본, 소급 정정은 직전 마감 기간만(정산서 v+1 재발행)
- MG 선급금은 정산 잔액 밖 관리, 마감 시 해당 앨범 위탁 정산액에서 자동 회수
- org_id 멀티테넌시, 금액 KRW bigint, VAT 10% 라인별 절사

## 작업 규칙 (사용자 확정)

- 과설계 금지, 선택지 나열 대신 추천 1안 제시
- 진행 보고는 실제 실행 결과와 대조하고, 검증 안 된 항목은 명시적으로 표시
- git 커밋은 사용자가 요청할 때만
- UI 문구는 회계 용어 대신 순화 표현 사용 (역분개→취소 등, web/CLAUDE.md 참고)

## 검증

```bash
cd server && ./gradlew test   # GoldenScenarioIT(① 10) + ConsignmentScenarioIT(② 8) + contextLoads
```

두 IT는 한 달치 운영을 실제 API로 수행해 손계산 숫자와 전액 대조한다. 서로 다른 org를 써서
마감 기간 충돌을 피한다. 테스트 DB `cratebox_test`는 매 실행 시 clean+migrate.
