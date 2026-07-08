-- cratebox Phase ① 스키마. 설계 근거: docs/DATA-MODEL.md

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

-- 기획사(LABEL)와 거래처(RETAILER)의 통합 상대방 테이블
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
  settlement_basis    varchar(15) check (settlement_basis in ('SELL_IN','SELL_THROUGH')),
  default_supply_rate numeric(5,4) check (default_supply_rate between 0 and 1),
  late_return_mode    varchar(15) not null default 'CARRY_FORWARD'
                      check (late_return_mode in ('CARRY_FORWARD','RESTATE')),
  active              boolean not null default true,
  created_at          timestamptz not null default now(),
  check (kind <> 'RETAILER' or settlement_basis is not null)
);
create index on party (org_id, kind);

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
  name         varchar(100) not null,
  release_date date
);

create table sku (
  id               bigint generated always as identity primary key,
  org_id           bigint not null references org(id),
  album_version_id bigint not null references album_version(id),
  barcode          varchar(30) not null,
  name             varchar(200) not null,
  list_price       bigint not null check (list_price >= 0),
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

-- ─── 정산 기간 (문서가 소급 지정을 참조하므로 문서보다 먼저) ─────

create table settlement_period (
  id         bigint generated always as identity primary key,
  org_id     bigint not null references org(id),
  year_month char(7) not null,
  status     varchar(10) not null default 'OPEN' check (status in ('OPEN','CLOSED')),
  closed_at  timestamptz,
  closed_by  bigint references app_user(id),
  unique (org_id, year_month)
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
  restate_period_id  bigint references settlement_period(id),  -- 소급 정정 대상 기간. null = 차기 이월
  memo               text,
  created_by         bigint not null references app_user(id),
  created_at         timestamptz not null default now(),
  posted_at          timestamptz,
  reversal_of_doc_id bigint references stock_doc(id),
  reversed_by_doc_id bigint references stock_doc(id),
  unique (org_id, doc_no)
);
create index on stock_doc (org_id, doc_type, status);
create index on stock_doc (org_id, occurred_on);

create table stock_doc_line (
  id         bigint generated always as identity primary key,
  org_id     bigint not null references org(id),
  doc_id     bigint not null references stock_doc(id),
  line_no    int not null,
  sku_id     bigint not null references sku(id),
  qty        int not null check (qty <> 0),
  unit_price bigint check (unit_price >= 0),
  note       varchar(200),
  unique (doc_id, line_no)
);
create index on stock_doc_line (doc_id);

-- ─── 재고 원장 (수량 차원) ──────────────────────────────

create table inventory_entry (
  id             bigint generated always as identity primary key,
  org_id         bigint not null references org(id),
  doc_line_id    bigint not null references stock_doc_line(id),
  entry_type     varchar(20) not null,
  sku_id         bigint not null references sku(id),
  location_id    bigint not null references location(id),
  owner_party_id bigint references party(id),  -- null = 자사 소유(사입)
  qty_delta      int not null check (qty_delta <> 0),
  occurred_on    date not null,
  recorded_at    timestamptz not null default now(),
  reversal_of_id bigint references inventory_entry(id)
);
create index on inventory_entry (org_id, sku_id, location_id);
create index on inventory_entry (org_id, occurred_on);
create index on inventory_entry (doc_line_id);

-- 잔량 캐시: 원장과 같은 트랜잭션에서 갱신. 음수 재고 차단 지점
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

-- ─── 정산 원장 (금액 차원) ──────────────────────────────
-- 부호: 양수 = 채권(상대가 줄 돈), 음수 = 채무(우리가 줄 돈)

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

create table settlement_entry (
  id                   bigint generated always as identity primary key,
  org_id               bigint not null references org(id),
  counterparty_id      bigint not null references party(id),
  doc_line_id          bigint references stock_doc_line(id),
  payment_id           bigint references payment(id),
  entry_type           varchar(20) not null,
  sku_id               bigint references sku(id),
  qty                  int,
  unit_price           bigint,
  supply_amount        bigint not null,
  vat_amount           bigint not null,
  amount               bigint not null,
  settlement_period_id bigint references settlement_period(id),  -- null = 미마감(이월 대기)
  occurred_on          date not null,
  recorded_at          timestamptz not null default now(),
  reversal_of_id       bigint references settlement_entry(id),
  check ((doc_line_id is not null) or (payment_id is not null))
);
create index on settlement_entry (org_id, counterparty_id, settlement_period_id);
create index on settlement_entry (org_id, occurred_on);

-- ─── 정산서 (불변 스냅숏 + 버전) ────────────────────────

create table statement (
  id              bigint generated always as identity primary key,
  org_id          bigint not null references org(id),
  period_id       bigint not null references settlement_period(id),
  counterparty_id bigint not null references party(id),
  kind            varchar(20) not null check (kind in ('RETAILER','LABEL_PURCHASE')),
  version         int not null default 1,
  opening_balance bigint not null,
  charge_supply   bigint not null,
  charge_vat      bigint not null,
  charge_total    bigint not null,
  payment_total   bigint not null,
  closing_balance bigint not null,
  generated_at    timestamptz not null default now(),
  generated_by    bigint not null references app_user(id),
  unique (org_id, period_id, counterparty_id, version)
);

create table statement_line (
  id            bigint generated always as identity primary key,
  statement_id  bigint not null references statement(id),
  sku_id        bigint references sku(id),
  label         varchar(200) not null,
  entry_type    varchar(20) not null,
  qty           int,
  unit_price    bigint,
  supply_amount bigint not null,
  vat_amount    bigint not null,
  amount        bigint not null
);
create index on statement_line (statement_id);

-- 문서번호 채번
create table doc_no_seq (
  org_id  bigint not null,
  prefix  varchar(10) not null,
  last_no int not null,
  primary key (org_id, prefix)
);

-- ─── 원장 불변성 강제 ───────────────────────────────────

-- 재고 원장: 모든 UPDATE/DELETE 금지
create function forbid_ledger_mutation() returns trigger language plpgsql as $$
begin
  raise exception '% is append-only', tg_table_name;
end $$;

create trigger inventory_entry_append_only
  before update or delete on inventory_entry
  for each row execute function forbid_ledger_mutation();

-- 정산 원장: DELETE 금지, UPDATE는 마감 도장(settlement_period_id)만 허용
create function settlement_entry_guard() returns trigger language plpgsql as $$
begin
  if tg_op = 'DELETE' then
    raise exception 'settlement_entry is append-only';
  end if;
  if to_jsonb(old) - 'settlement_period_id' <> to_jsonb(new) - 'settlement_period_id' then
    raise exception 'settlement_entry is append-only (only period stamping is allowed)';
  end if;
  return new;
end $$;

create trigger settlement_entry_append_only
  before update or delete on settlement_entry
  for each row execute function settlement_entry_guard();
