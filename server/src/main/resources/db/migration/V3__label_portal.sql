-- Phase ② 기획사 포털: 역할 기반 접근. LABEL 사용자는 자기 기획사 데이터만 읽는다.
alter table app_user add column role varchar(10) not null default 'ADMIN'
  check (role in ('ADMIN','LABEL'));
alter table app_user add column party_id bigint references party(id);
alter table app_user add constraint app_user_label_needs_party
  check ((role = 'LABEL') = (party_id is not null));
