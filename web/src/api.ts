// 세션 쿠키 기반 API 클라이언트. 401 → 로그인 화면으로
export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, { credentials: 'same-origin', ...init })
  if (res.status === 401 && !path.endsWith('/auth/login')) {
    window.location.href = '/login'
    throw new ApiError(401, '로그인이 필요합니다')
  }
  if (!res.ok) {
    const body = await res.json().catch(() => null)
    throw new ApiError(res.status, body?.message ?? `요청 실패 (${res.status})`)
  }
  const text = await res.text()
  return (text ? JSON.parse(text) : null) as T
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, {
      method: 'POST',
      headers: body !== undefined ? { 'Content-Type': 'application/json' } : {},
      body: body !== undefined ? JSON.stringify(body) : undefined,
    }),
  put: <T>(path: string, body: unknown) =>
    request<T>(path, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  del: (path: string) => request<void>(path, { method: 'DELETE' }),
}

// ── 타입 (백엔드 DTO 대응) ──────────────────────────

export interface Me { userId: number; username: string; displayName: string; role: 'ADMIN' | 'LABEL' }

/** 발행사(운영사) 정보 — 정산서 머리글에 표기 */
export interface Org {
  name: string; bizRegNo?: string | null; ceoName?: string | null
  address?: string | null; phone?: string | null; email?: string | null
}

export interface Party {
  id: number; kind: 'LABEL' | 'RETAILER'; name: string
  bizRegNo?: string; contactName?: string; phone?: string; email?: string; memo?: string
  settlementBasis?: 'SELL_IN' | 'SELL_THROUGH'; defaultSupplyRate?: number
  lateReturnMode: 'CARRY_FORWARD' | 'RESTATE'; active: boolean
}

export interface Album { id: number; labelPartyId: number; title: string; artistName: string; releaseDate?: string }
export interface AlbumVersion { id: number; albumId: number; name: string; releaseDate?: string }
export interface Sku { id: number; albumVersionId: number; barcode: string; name: string; listPrice: number; active: boolean }
export interface SkuFlat {
  id: number; name: string; barcode: string; listPrice: number; active: boolean
  albumVersionId: number; versionName: string; albumId: number; albumTitle: string; artistName: string
  labelPartyId: number; labelName: string; agreementKind?: 'PURCHASE' | 'CONSIGNMENT' | null
}

export interface Agreement {
  albumId: number; albumTitle: string; labelPartyId: number; labelName: string
  kind: 'PURCHASE' | 'CONSIGNMENT'; commissionRate?: number | null; memo?: string
}

export interface Advance {
  id: number; labelPartyId: number; labelName: string; albumId: number; albumTitle: string
  amount: number; recouped: number; remaining: number; paidOn: string; memo?: string
}

export interface Location {
  id: number; kind: 'WAREHOUSE' | 'RETAILER'; name: string; retailerPartyId?: number; active: boolean
}

export type DocType =
  | 'PURCHASE_IN' | 'PURCHASE_RETURN' | 'SALE_OUT' | 'CONSIGN_PLACE' | 'SALES_REPORT'
  | 'CUSTOMER_RETURN' | 'CONSIGN_RECALL' | 'TRANSFER' | 'ADJUST' | 'OPENING'
  | 'CONSIGN_IN' | 'RETURN_TO_OWNER' | 'DIRECT_SALE'

export const DOC_TYPE_LABEL: Record<DocType, string> = {
  PURCHASE_IN: '사입입고', PURCHASE_RETURN: '매입반품', SALE_OUT: '판매출고',
  CONSIGN_PLACE: '진열출고', SALES_REPORT: '판매보고', CUSTOMER_RETURN: '거래처반품',
  CONSIGN_RECALL: '회수', TRANSFER: '창고이동', ADJUST: '실사조정', OPENING: '기초재고',
  CONSIGN_IN: '수탁입고', RETURN_TO_OWNER: '위탁반납', DIRECT_SALE: '현장판매',
}

export interface DocLine {
  id?: number; lineNo?: number; skuId: number; qty: number; unitPrice?: number | null
  ownerPartyId?: number | null; note?: string
}
export interface StockDoc {
  id: number; docNo: string; docType: DocType; status: 'DRAFT' | 'POSTED' | 'REVERSED'
  counterpartyId?: number; locationFromId?: number; locationToId?: number
  occurredOn: string; restatePeriodId?: number; memo?: string
  postedAt?: string; reversalOfDocId?: number; reversedByDocId?: number
  lines: DocLine[]
}
export interface DocSummary {
  id: number; docNo: string; docType: DocType; status: string
  counterpartyId?: number; counterpartyName?: string
  locationFromName?: string; locationToName?: string
  occurredOn: string; memo?: string; lineCount: number; totalQty: number
  reversalOfDocId?: number; reversedByDocId?: number
}

export interface StockRow {
  skuId: number; skuName: string; barcode: string; albumTitle: string; versionName: string
  locationId: number; locationName: string; locationKind: string
  ownerPartyId?: number | null; ownerName?: string | null; qty: number
}
export interface FirstWeekRow {
  albumVersionId: number; albumTitle: string; versionName: string
  releaseDate: string; windowEnd: string; units: number
}
export interface BalanceRow { counterpartyId: number; name: string; kind: string; balance: number; unstamped: number }

export interface Period { id: number; yearMonth: string; status: string; closedAt?: string }
export interface StatementSummary {
  id: number; periodId: number; yearMonth: string; counterpartyId: number; counterpartyName: string
  kind: string; version: number; openingBalance: number; chargeSupply: number; chargeVat: number
  chargeTotal: number; paymentTotal: number; advanceTotal: number; closingBalance: number
  generatedAt: string; latest: boolean
}
export interface StatementLine {
  skuId?: number; label: string; entryType: string; qty?: number; unitPrice?: number
  supplyAmount: number; vatAmount: number; amount: number
}
export interface StatementDetail { header: StatementSummary; issuer: Org; lines: StatementLine[] }

export interface Payment {
  id: number; counterpartyId: number; counterpartyName: string; direction: 'IN' | 'OUT'
  amount: number; occurredOn: string; memo?: string; reversed: boolean
}

// ── 기획사 포털 ─────────────────────────────────────

export interface PortalSummary { labelName: string; balance: number; unstamped: number }
export interface PortalStockRow {
  albumTitle: string; versionName: string; skuName: string; barcode: string
  locationName: string; locationKind: string; qty: number
}
export interface PortalStatement {
  id: number; yearMonth: string; kind: string; version: number
  openingBalance: number; chargeSupply: number; chargeVat: number; chargeTotal: number
  paymentTotal: number; advanceTotal: number; closingBalance: number; generatedAt: string; latest: boolean
}
export interface PortalStatementDetail { header: PortalStatement; issuer: Org; lines: StatementLine[] }
export interface PortalLedgerRow {
  occurredOn: string; entryType: string; skuName?: string; qty?: number
  amount: number; yearMonth?: string; reversal: boolean
}

export const fmt = (n: number | undefined | null) => (n ?? 0).toLocaleString('ko-KR')

/** 로컬 기준 오늘 날짜 (toISOString은 UTC라 한국에선 오전 9시 전에 전날이 나온다) */
export const todayLocal = () => {
  const d = new Date()
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset())
  return d.toISOString().slice(0, 10)
}
