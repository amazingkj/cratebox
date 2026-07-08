# cratebox 데이터 모델 — 사입/위탁 통합 원장

## 1. 설계 원칙

1. **문서와 전기의 분리.** 사용자는 문서(`stock_doc`)를 다루고, 확정(POST) 시 원장 엔트리가 생성된다. 원장은 append-only — DB 트리거로 UPDATE/DELETE를 차단한다. 수정은 역분개뿐이다. 역분개 엔트리는 원본의 `entry_type`을 유지하고 부호만 반전, `reversal_of_id`로 표시한다(집계 쿼리가 역분개를 자동 반영하게 하기 위함).
2. **원장은 두 개, 전기 규칙은 하나.** 수량(재고 원장)과 금액(정산 원장)은 차원이 달라 테이블을 분리하되, 불변성·부호 규약·역분개·문서 참조 규칙을 공유한다. 위탁 판매 1건 = 재고 1행 + 정산 2행.
3. **거래 모델의 차이 = 전기 매트릭스의 행 차이.** 스키마 차이가 아니다.
   - 기획사→유통사 **위탁**은 재고 원장의 **소유 차원**(`owner_party_id`, null=자사 소유)으로,
   - 유통사→거래처 **판매분 정산(sell-through)**은 **위치 차원**(`location.kind`: 자사 창고 | 거래처 매장)으로 표현된다.
   - 두 차원은 직교한다: 위탁받은 음반을 판매분 거래처 매장에 진열한 상태 = `owner=기획사, location=거래처`. Phase ②는 테이블 추가 없이(계약·선급금 제외) 매트릭스 행 추가로 구현된다 — 이것이 설계 검증 기준.
4. **정산 원장 부호 규약**: 양수 = 상대가 우리에게 줄 돈(채권 증가), 음수 = 우리가 상대에게 줄 돈(채무 증가). 상대방별 잔액 = `SUM(amount)`.
5. **마감 = 도장(stamping).** 정산 엔트리는 생성 시 `settlement_period_id = NULL`. 월 마감이 미배정 엔트리(`occurred_on ≤ 월말`)에 기간을 도장 찍고 정산서를 만든다. 마감 후 반품은 자동으로 다음 마감에 포함(차기 이월). 소급 정정은 마감된 기간을 명시 지정해 도장 → 그 정산서를 version+1로 재발행.

## 2. 전기 매트릭스

| 문서 | 재고 원장 | 정산 원장 | Phase |
|---|---|---|---|
| 사입입고 `PURCHASE_IN` | `+qty @창고` | 기획사 `−매입가·qty` | ① |
| 매입반품 `PURCHASE_RETURN` | `−qty @창고` | 기획사 `+매입가·qty` | ① |
| 판매출고 `SALE_OUT` (SELL_IN 거래처) | `−qty @창고` | 거래처 `+공급가·qty` | ① |
| 거래처반품 `CUSTOMER_RETURN` (SELL_IN) | `+qty @창고` | 거래처 `−공급가·qty` | ① |
| 진열출고 `CONSIGN_PLACE` (SELL_THROUGH) | `−qty @창고, +qty @거래처` | — | ① |
| 판매보고 `SALES_REPORT` (SELL_THROUGH) | `−qty @거래처` | 거래처 `+공급가·qty` | ① |
| 회수 `CONSIGN_RECALL` (SELL_THROUGH 미판매분) | `−qty @거래처, +qty @창고` | — | ① |
| 창고이동 `TRANSFER` | `−from, +to` | — | ① |
| 실사조정 `ADJUST` / 기초재고 `OPENING` | `±qty @위치` | — | ① |
| 입금 `PAYMENT_IN` / 지급 `PAYMENT_OUT` | — | 상대 `−금액` / `+금액` | ① |
| 수탁입고 `CONSIGN_IN` | `+qty @창고 (owner=기획사)` | — | ② |
| 위탁 판매출고/판매보고 (라인 owner=기획사) | `−qty (owner=기획사)` | 거래처 `+공급가·qty`, 기획사 `CONSIGN_SALE` `−기획사몫` | ② |
| 위탁 거래처반품 (라인 owner=기획사) | `+qty (owner=기획사)` | 거래처 `−`, 기획사 `CONSIGN_RETURN` `+기획사몫` | ② |
| 위탁 반납 `RETURN_TO_OWNER` | `−qty @창고 (owner=기획사)` | — | ② |
| MG 회수 `ADVANCE_RECOUP` | — | 기획사 `+` (마감 시 해당 앨범 위탁 정산액에서 자동 차감, `advance_id` 참조) | ② |

기획사몫 = **절사(|공급가액| × (1 − 수수료율))** + 그에 대한 VAT. 기획사 엔트리는 단가 미기록.
위탁 판매 1건의 정산 엔트리 합 = 유통사 수수료 마진이 원장에서 그대로 읽힌다.
위탁 진열/회수/이동/실사/기초재고는 ①과 같은 행에 라인 `owner_party_id`만 실려 소유 풀을 유지한다.

모든 문서의 위치 규약: 단일 위치 문서는 방향에 따라 `location_from`(출고류) 또는 `location_to`(입고류), `ADJUST`/`OPENING`은 `location_to`, 이동류(`CONSIGN_PLACE`/`CONSIGN_RECALL`/`TRANSFER`)는 둘 다.

## 3. DDL (Phase ①)

Flyway `V1__schema.sql`의 원본. 모든 테이블에 `org_id`(멀티테넌시), 유니크 제약도 org 스코프.

```sql
create table org (
  id         bigint generated always as identity primary key,
  name       varchar(100) not null,
  created_at timestamptz  not null default now()
);

create table app_user (
  id            bigint generated always as identity primary key,
  org_id        bigint not null references org(id),
  username      varchar(50) not null unique,
  password_hash varchar(100) not null,
  display_name  varchar(50) not null,
  created_at    timestamptz not null default now()
);

-- 기획사(LABEL)와 거래처(RETAILER)의 통합 상대방 테이블.
-- 정산 원장이 상대방을 단일 FK로 참조하기 위해 하나로 둔다.
create table party (
  id                  bigint generated always as identity primary key,
  org_id              bigint not null references org(id),
  kind                varchar(10) not null check (kind in ('LABEL','RETAILER')),
  name                varchar(100) not null,
  biz_reg_no          varchar(20),
  contact_name        varchar(50),
  phone               varchar(30),
  email               varchar(100),
  memo                text,
  -- RETAILER 전용 --
  settlement_basis    varchar(15) check (settlement_basis in ('SELL_IN','SELL_THROUGH')),
  default_supply_rate numeric(5,4) check (default_supply_rate between 0 and 1),
  late_return_mode    varchar(15) not null default 'CARRY_FORWARD'
                      check (late_return_mode in ('CARRY_FORWARD','RESTATE')),
  active              boolean not null default true,
  created_at          timestamptz not null default now(),
  check (kind <> 'RETAILER' or settlement_basis is not null)
);

create table album (
  id             bigint generated always as identity primary key,
  org_id         bigint not null references org(id),
  label_party_id bigint not null references party(id),
  title          varchar(200) not null,
  artist_name    varchar(200) not null,
  release_date   date
);

create table album_version (
  id           bigint generated always as identity primary key,
  org_id       bigint not null references org(id),
  album_id     bigint not null references album(id),
  name         varchar(100) not null,        -- 예: 'A ver.', '한정반'
  release_date date                           -- null이면 앨범 발매일 상속
);

create table sku (
  id               bigint generated always as identity primary key,
  org_id           bigint not null references org(id),
  album_version_id bigint not null references album_version(id),
  barcode          varchar(30) not null,
  name             varchar(200) not null,
  list_price       bigint not null check (list_price >= 0),  -- 소비자가(원)
  active           boolean not null default true,
  unique (org_id, barcode)
);

-- 재고 위치: 자사 창고 + 판매분(SELL_THROUGH) 거래처 매장
create table location (
  id                bigint generated always as identity primary key,
  org_id            bigint not null references org(id),
  kind              varchar(10) not null check (kind in ('WAREHOUSE','RETAILER')),
  name              varchar(100) not null,
  retailer_party_id bigint references party(id),
  active            boolean not null default true,
  check ((kind = 'RETAILER') = (retailer_party_id is not null)),
  unique (org_id, retailer_party_id)
);

-- ─── 문서 ───────────────────────────────────────────────

create table stock_doc (
  id                 bigint generated always as identity primary key,
  org_id             bigint not null references org(id),
  doc_no             varchar(20) not null,
  doc_type           varchar(20) not null check (doc_type in
    ('PURCHASE_IN','PURCHASE_RETURN','SALE_OUT','CONSIGN_PLACE','SALES_REPORT',
     'CUSTOMER_RETURN','CONSIGN_RECALL','TRANSFER','ADJUST','OPENING')),
  status             varchar(10) not null default 'DRAFT'
                     check (status in ('DRAFT','POSTED','REVERSED')),
  counterparty_id    bigint references party(id),
  location_from_id   bigint references location(id),
  location_to_id     bigint references location(id),
  occurred_on        date not null,
  -- 소급 정정: 마감된 기간에 배정하려면 지정. null = 미배정(차기 이월)
  restate_period_id  bigint,                  -- FK는 settlement_period 정의 후 추가
  memo               text,
  created_by         bigint not null references app_user(id),
  created_at         timestamptz not null default now(),
  posted_at          timestamptz,
  reversal_of_doc_id bigint references stock_doc(id),  -- 이 문서가 역분개 문서인 경우 원본
  reversed_by_doc_id bigint references stock_doc(id),  -- 이 문서를 뒤집은 역분개 문서
  unique (org_id, doc_no)
);

create table stock_doc_line (
  id         bigint generated always as identity primary key,
  org_id     bigint not null references org(id),
  doc_id     bigint not null references stock_doc(id),
  line_no    int not null,
  sku_id     bigint not null references sku(id),
  qty        int not null check (qty <> 0),   -- ADJUST·SALES_REPORT만 음수 허용(앱에서 검증)
  unit_price bigint check (unit_price >= 0),  -- 공급가액 단가. 가격 문서만 필수(앱에서 검증)
  note       varchar(200),
  unique (doc_id, line_no)
);

-- ─── 재고 원장 (수량 차원) ──────────────────────────────

create table inventory_entry (
  id             bigint generated always as identity primary key,
  org_id         bigint not null references org(id),
  doc_line_id    bigint not null references stock_doc_line(id),
  entry_type     varchar(20) not null,        -- 문서 타입 + 방향(_IN/_OUT), 역분개도 원본 타입 유지
  sku_id         bigint not null references sku(id),
  location_id    bigint not null references location(id),
  owner_party_id bigint references party(id), -- null = 자사 소유(사입). ②에서 위탁 기획사
  qty_delta      int not null check (qty_delta <> 0),
  occurred_on    date not null,
  recorded_at    timestamptz not null default now(),
  reversal_of_id bigint references inventory_entry(id)
);
create index on inventory_entry (org_id, sku_id, location_id);
create index on inventory_entry (org_id, occurred_on);
create index on inventory_entry (doc_line_id);

-- 잔량 캐시: 원장과 같은 트랜잭션에서 갱신. 음수 재고 차단 지점(행 잠금 후 검증)
create table stock_balance (
  id             bigint generated always as identity primary key,
  org_id         bigint not null references org(id),
  sku_id         bigint not null references sku(id),
  location_id    bigint not null references location(id),
  owner_party_id bigint references party(id),
  qty            int not null check (qty >= 0),
  updated_at     timestamptz not null default now()
);
create unique index stock_balance_key on stock_balance
  (org_id, sku_id, location_id, coalesce(owner_party_id, 0));

-- ─── 정산 ───────────────────────────────────────────────

create table settlement_period (
  id         bigint generated always as identity primary key,
  org_id     bigint not null references org(id),
  year_month char(7) not null,                -- 'YYYY-MM'
  status     varchar(10) not null default 'OPEN' check (status in ('OPEN','CLOSED')),
  closed_at  timestamptz,
  closed_by  bigint references app_user(id),
  unique (org_id, year_month)
);

alter table stock_doc add constraint stock_doc_restate_period_fk
  foreign key (restate_period_id) references settlement_period(id);

-- 정산 원장 (금액 차원). 부호: 양수 = 채권(상대가 줄 돈), 음수 = 채무(우리가 줄 돈)
create table settlement_entry (
  id                   bigint generated always as identity primary key,
  org_id               bigint not null references org(id),
  counterparty_id      bigint not null references party(id),
  doc_line_id          bigint references stock_doc_line(id),  -- 입금/지급은 null
  payment_id           bigint,                 -- FK는 payment 정의 후 추가
  entry_type           varchar(20) not null,   -- SALE, SALE_RETURN, PURCHASE, PURCHASE_RETURN,
                                               -- PAYMENT_IN, PAYMENT_OUT / ②: CONSIGN_SALE, ADVANCE_RECOUP
  sku_id               bigint references sku(id),
  qty                  int,
  unit_price           bigint,                 -- 공급가액 단가 스냅숏
  supply_amount        bigint not null,        -- 부호 포함 공급가액
  vat_amount           bigint not null,
  amount               bigint not null,        -- supply + vat. 잔액 = SUM(amount)
  settlement_period_id bigint references settlement_period(id),  -- null = 미마감(이월 대기)
  occurred_on          date not null,
  recorded_at          timestamptz not null default now(),
  reversal_of_id       bigint references settlement_entry(id),
  check ((doc_line_id is not null) or (payment_id is not null))
);
create index on settlement_entry (org_id, counterparty_id, settlement_period_id);
create index on settlement_entry (org_id, occurred_on);

create table payment (
  id              bigint generated always as identity primary key,
  org_id          bigint not null references org(id),
  counterparty_id bigint not null references party(id),
  direction       varchar(3) not null check (direction in ('IN','OUT')),
  amount          bigint not null check (amount > 0),
  occurred_on     date not null,
  memo            text,
  created_by      bigint not null references app_user(id),
  created_at      timestamptz not null default now(),
  reversed        boolean not null default false
);

alter table settlement_entry add constraint settlement_entry_payment_fk
  foreign key (payment_id) references payment(id);

-- 정산서: 불변 스냅숏 + 버전 (소급 정정 시 version+1 재발행)
create table statement (
  id              bigint generated always as identity primary key,
  org_id          bigint not null references org(id),
  period_id       bigint not null references settlement_period(id),
  counterparty_id bigint not null references party(id),
  kind            varchar(20) not null check (kind in ('RETAILER','LABEL_PURCHASE')),
  version         int not null default 1,
  opening_balance bigint not null,
  charge_supply   bigint not null,   -- 당기 발생 공급가액 (출고−반품 순액)
  charge_vat      bigint not null,
  charge_total    bigint not null,
  payment_total   bigint not null,   -- 당기 입금/지급 (부호 포함)
  closing_balance bigint not null,
  generated_at    timestamptz not null default now(),
  generated_by    bigint not null references app_user(id),
  unique (org_id, period_id, counterparty_id, version)
);

create table statement_line (
  id            bigint generated always as identity primary key,
  statement_id  bigint not null references statement(id),
  sku_id        bigint references sku(id),
  label         varchar(200) not null,  -- 표시용 스냅숏 (SKU명 또는 '입금' 등)
  entry_type    varchar(20) not null,
  qty           int,
  unit_price    bigint,
  supply_amount bigint not null,
  vat_amount    bigint not null,
  amount        bigint not null
);

-- 문서번호 채번
create table doc_no_seq (
  org_id  bigint not null,
  prefix  varchar(10) not null,   -- 예: 'SO-2607'
  last_no int not null,
  primary key (org_id, prefix)
);

-- ─── 원장 불변성 강제 ───────────────────────────────────

create function forbid_ledger_mutation() returns trigger language plpgsql as $$
begin
  raise exception '% is append-only', tg_table_name;
end $$;

create trigger inventory_entry_append_only
  before update or delete on inventory_entry
  for each row execute function forbid_ledger_mutation();

create trigger settlement_entry_append_only
  before update or delete on settlement_entry
  for each row execute function forbid_ledger_mutation();
```

> `settlement_entry.settlement_period_id`는 마감 시 NULL→기간 도장이 필요하므로, 트리거는 그 컬럼만 예외로 허용하는 형태로 실제 마이그레이션에서 조정한다 (`update`에서 기간 컬럼 외 변경 시 거부).

## 4. 파생 조회 (스키마 불요)

- **재고 현황**: `stock_balance`를 위치·소유별로 피벗. 거래처 매장 잔량 = 판매분 거래처의 미판매(회수 대상) 재고.
- **상대방 잔액**: `SUM(settlement_entry.amount) GROUP BY counterparty_id`. 양수=미수, 음수=미지급.
- **초동**: 버전 발매일부터 7일간 `Σ(SALE_OUT, SALES_REPORT 수량) − Σ(CUSTOMER_RETURN 수량)`.
- **불변식(테스트가 상시 검증)**: 모든 키에 대해 `Σ inventory_entry.qty_delta == stock_balance.qty`.

## 5. Phase ② 확장 (V2·V3에서 확정)

설계 원칙 3의 검증대로 **원장·잔량·마감 메커니즘 변경 없이** 매트릭스 행 추가로 구현되었다. 스키마 델타 전문은 `V2__phase2_consignment.sql`, `V3__label_portal.sql`.

- `trade_agreement` — **앨범 단위**(기획사는 `album.label_party_id`로 유도, `unique(org_id, album_id)`): kind PURCHASE|CONSIGNMENT, 위탁이면 `commission_rate` 필수. 위탁 라인 전기·수탁입고·MG의 전제 조건.
- `advance` — MG 선급금(기획사×앨범, 지급액·지급일). 잔여 = `amount − Σ(advance_id 참조 정산 엔트리)`. 정산 잔액 밖에서 관리.
- `stock_doc_line.owner_party_id` — 소유 차원 활성화(null=자사). 검증: 소유자 = 앨범의 기획사 + 위탁 계약 존재.
- `settlement_entry.advance_id` — MG 회수 엔트리의 참조(체크 제약을 doc_line|payment|advance 3원으로 확장).
- `statement.advance_total` + kind `LABEL_CONSIGN` — 기말 잔액 = 이월 + 당기 + 입금/지급 + MG 차감. kind는 당기에 위탁 엔트리가 있으면 LABEL_CONSIGN.
- `app_user.role`(ADMIN|LABEL) + `party_id` — 기획사 포털(읽기전용, `/api/portal/*`, party 스코프).

마감 순서: 도장(stamping) → **MG 회수**(앨범 단위, 오래된 선급금부터, 같은 close 내 이중 회수 방지) → 정산서 생성. 소급 정정이 마감된 기간의 위탁 정산액을 바꿔도 회수는 재계산하지 않는다(합계 정합 유지, MG 조기 회수만 발생 — 명시된 단순화).

## 6. Phase ③ 확장 (스키마 델타 예고)

**③ 음원**: `track`(album 하위), `royalty_split`(트랙×수취인×비율), `dsp_report_upload`(원본 파일)·`dsp_report_row`(정규화 스테이징) → 확정 시 `settlement_entry`(entry_type `DIGITAL_ROYALTY`)로 전기. 정산·마감·정산서 파이프라인을 그대로 재사용하는 것이 ③의 요점.
