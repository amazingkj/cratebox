-- 발행사(운영사) 정보: 정산서 머리글·인쇄물에 표기
alter table org add column biz_reg_no varchar(20);
alter table org add column ceo_name   varchar(50);
alter table org add column address    varchar(200);
alter table org add column phone      varchar(30);
alter table org add column email      varchar(100);

-- 현장판매(행사·팝업 직판): 재고 차감 + 위탁 라인은 기획사몫 정산(CONSIGN_SALE).
-- 상대방 없음 — 현장 수금이므로 거래처 채권을 만들지 않는다 (단순화, docs/DATA-MODEL.md §2)
alter table stock_doc drop constraint stock_doc_doc_type_check;
alter table stock_doc add constraint stock_doc_doc_type_check check (doc_type in
  ('PURCHASE_IN','PURCHASE_RETURN','SALE_OUT','CONSIGN_PLACE','SALES_REPORT',
   'CUSTOMER_RETURN','CONSIGN_RECALL','TRANSFER','ADJUST','OPENING',
   'CONSIGN_IN','RETURN_TO_OWNER','DIRECT_SALE'));
