-- Phase ② 위탁(consignment): 소유 차원 활성화 + 거래 계약 + MG 선급금
-- 원장·잔량·마감 메커니즘은 V1 그대로. 위탁은 전기 매트릭스 행 추가로 구현된다.

-- 새 문서 타입: 수탁입고 / 위탁 반납
alter table stock_doc drop constraint stock_doc_doc_type_check;
alter table stock_doc add constraint stock_doc_doc_type_check check (doc_type in
  ('PURCHASE_IN','PURCHASE_RETURN','SALE_OUT','CONSIGN_PLACE','SALES_REPORT',
   'CUSTOMER_RETURN','CONSIGN_RECALL','TRANSFER','ADJUST','OPENING',
   'CONSIGN_IN','RETURN_TO_OWNER'));

-- 라인 소유 구분: null = 자사(사입) 재고, 값 = 위탁 기획사 재고 풀
alter table stock_doc_line add column owner_party_id bigint references party(id);

-- 거래 계약: 앨범 단위 (기획사는 album.label_party_id로 결정된다)
create table trade_agreement (
  id              bigint generated always as identity primary key,
  org_id          bigint not null references org(id),
  album_id        bigint not null references album(id),
  kind            varchar(15) not null check (kind in ('PURCHASE','CONSIGNMENT')),
  commission_rate numeric(5,4) check (commission_rate between 0 and 1),  -- 위탁 수수료율 (공급가액 대비)
  memo            text,
  created_at      timestamptz not null default now(),
  unique (org_id, album_id),
  check (kind <> 'CONSIGNMENT' or commission_rate is not null)
);

-- MG 선급금: 지급 사실의 기록. 정산 잔액 밖에서 관리되고,
-- 회수(recoup)만 마감 시 settlement_entry(ADVANCE_RECOUP, 양수)로 원장에 들어간다.
create table advance (
  id             bigint generated always as identity primary key,
  org_id         bigint not null references org(id),
  label_party_id bigint not null references party(id),
  album_id       bigint not null references album(id),
  amount         bigint not null check (amount > 0),
  paid_on        date not null,
  memo           text,
  created_by     bigint not null references app_user(id),
  created_at     timestamptz not null default now()
);

alter table settlement_entry add column advance_id bigint references advance(id);
alter table settlement_entry drop constraint settlement_entry_check;
alter table settlement_entry add constraint settlement_entry_check
  check ((doc_line_id is not null) or (payment_id is not null) or (advance_id is not null));
create index on settlement_entry (advance_id) where advance_id is not null;

-- 정산서: 위탁 정산서 kind + MG 차감 합계
alter table statement drop constraint statement_kind_check;
alter table statement add constraint statement_kind_check
  check (kind in ('RETAILER','LABEL_PURCHASE','LABEL_CONSIGN'));
alter table statement add column advance_total bigint not null default 0;
