# web — React 프런트

React 19 + TS + Vite + TanStack Query + Tailwind 4. 빌드 결과는 server 정적 리소스로 들어가
**단일 배포물**이 된다: `npm run build` 후 백엔드 재시작해야 반영. 개발은 `npm run dev`(5173, /api → 8087 프록시).

## 구조

- `src/api.ts` — fetch 클라이언트 + 백엔드 DTO 대응 타입 전부 + `DOC_TYPE_LABEL`. 401이면 /login으로
- `src/ui.tsx` — 공용 컴포넌트: Card, Table, Input, Select, Button, Field, Badge, Money(음수 빨강)
- `src/csv.ts` — `downloadCsv(파일명, 헤더, 행들)`: UTF-8 BOM CSV, '엑셀 저장' 버튼들이 사용
- `src/pages/` — 화면 1파일 1페이지. Portal.tsx는 LABEL 역할 전용 셸(읽기전용 포털),
  FieldSale.tsx는 모바일 현장판매, Settings.tsx는 회사 정보+비밀번호
- 바코드 인식: `barcode-detector/ponyfill`(zxing-wasm) — iOS 포함 전 브라우저 동작.
  wasm은 `?url` import로 로컬 번들 (CDN 금지). 카메라는 보안 컨텍스트 필수 → 폰은 https 8443
- 라우팅: App.tsx. role=LABEL이면 PortalApp으로 분기

## UI 문구 규칙 (사용자 요청 — 회계 용어 순화)

- 코드/API는 reverse/REVERSED 유지, **화면에는 '역분개' 금지** → 취소/취소됨/취소 문서/(취소분)
- '미배정' 대신 '아직 정산되지 않은 거래'/'미마감'
- 문서 라인은 화면에서 '품목', 새 문서 화면엔 유형별 한 줄 설명(DOC_TYPE_DESC)
- 금액 표시는 Money/fmt(천단위), 부호 안내 문구(양수=받을 돈) 유지

## 디자인 토큰 ("창고의 장부" 컨셉 — 일관성 유지할 것)

- 팔레트: 페이지 bg-stone-50(웜그레이 종이), 카드 white+border-stone-200, 사이드바/헤더 bg-stone-950(비닐 블랙),
  주 액센트 emerald-700/800(장부 잉크 그린 — 버튼·링크·활성 내비), 위탁 태그 amber-50/300/800(크레이트 스티커),
  음수 금액만 red-600(회계 관례). **cool gray·slate·indigo 사용 금지**
- 폰트: Pretendard Variable(npm `pretendard`, 로컬 번들). 문서번호·바코드는 font-mono
- 시그니처: `Stamp` 컴포넌트(ui.tsx) — 확정·정산 확정 표시는 -rotate-2 스탬프. 마감=도장(stamping) 은유. 남용 금지
- 브랜드: crate<span emerald>box</span> 워드마크 (App Wordmark, Login, Portal 헤더)
- 내비: 섹션 라벨(운영/정산/기준정보) + 활성 항목 왼쪽 emerald 보더

## 관례

- 서버 상태는 전부 TanStack Query, 변이 후 invalidateQueries
- 확인이 필요한 파괴적 동작은 confirm()으로 한 줄 설명과 함께
- 인쇄는 window.print() + Tailwind `print:hidden`(사이드바·버튼류) — 정산서 화면에 적용됨
