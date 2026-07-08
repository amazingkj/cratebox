import { Link, Navigate, Route, Routes, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  api, fmt,
  type Me, type PortalLedgerRow, type PortalStatement, type PortalStatementDetail,
  type PortalStockRow, type PortalSummary,
} from '../api'
import { Button, Card, Money, Stamp, Table } from '../ui'
import { downloadCsv } from '../csv'

const ENTRY_LABEL: Record<string, string> = {
  SALE: '판매', SALE_RETURN: '반품', PURCHASE: '매입', PURCHASE_RETURN: '매입반품',
  PAYMENT_IN: '입금', PAYMENT_OUT: '지급',
  CONSIGN_SALE: '위탁판매', CONSIGN_RETURN: '위탁반품', ADVANCE_RECOUP: 'MG 차감',
}

/** 기획사 포털 전체 셸 (LABEL 역할 로그인 시) — 읽기전용 */
export default function PortalApp({ me }: { me: Me }) {
  return (
    <div className="min-h-screen bg-stone-50">
      <header className="bg-stone-950 text-white px-4 py-3 flex items-center justify-between sticky top-0 z-10 print:hidden">
        <span className="font-bold tracking-tight">crate<span className="text-emerald-400">box</span>
          <span className="ml-2 font-normal text-stone-400 text-sm">파트너 포털</span>
        </span>
        <span className="text-sm text-stone-400 flex items-center gap-3">
          {me.displayName}
          <button
            className="underline hover:text-white"
            onClick={async () => {
              await api.post('/api/auth/logout')
              window.location.href = '/login'
            }}
          >
            로그아웃
          </button>
        </span>
      </header>
      <main className="p-4 md:p-6 max-w-4xl mx-auto">
        <Routes>
          <Route path="/portal" element={<PortalHome />} />
          <Route path="/portal/statements/:id" element={<PortalStatementPage />} />
          <Route path="*" element={<Navigate to="/portal" replace />} />
        </Routes>
      </main>
    </div>
  )
}

function PortalHome() {
  const summary = useQuery({ queryKey: ['portal-summary'], queryFn: () => api.get<PortalSummary>('/api/portal/summary') })
  const stock = useQuery({ queryKey: ['portal-stock'], queryFn: () => api.get<PortalStockRow[]>('/api/portal/stock') })
  const statements = useQuery({ queryKey: ['portal-statements'], queryFn: () => api.get<PortalStatement[]>('/api/portal/statements') })
  const ledger = useQuery({ queryKey: ['portal-ledger'], queryFn: () => api.get<PortalLedgerRow[]>('/api/portal/ledger') })

  const totalStock = (stock.data ?? []).reduce((s, r) => s + r.qty, 0)

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold text-stone-900">{summary.data?.labelName}</h1>

      <Card>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-3 text-sm">
          <div>
            <p className="text-xs text-stone-400">정산 잔액</p>
            <p className="font-bold text-base tabular-nums">
              <Money value={summary.data?.balance ?? 0} />원
            </p>
            <p className="text-xs text-stone-400 mt-0.5">음수 = 유통사가 지급할 금액</p>
          </div>
          <div>
            <p className="text-xs text-stone-400">미마감분</p>
            <p className="tabular-nums"><Money value={summary.data?.unstamped ?? 0} />원</p>
          </div>
          <div>
            <p className="text-xs text-stone-400">유통사 보관 재고</p>
            <p className="tabular-nums">{fmt(totalStock)}개</p>
          </div>
        </div>
      </Card>

      <Card title="내 위탁 재고 (유통사 보관분)">
        <Table head={['앨범', '버전', 'SKU', '위치', '수량']}>
          {stock.data?.map((r, i) => (
            <tr key={i}>
              <td className="py-2 pr-4">{r.albumTitle}</td>
              <td className="py-2 pr-4">{r.versionName}</td>
              <td className="py-2 pr-4">{r.skuName}</td>
              <td className="py-2 pr-4">
                {r.locationName}
                {r.locationKind === 'RETAILER' && <span className="ml-1 text-xs text-amber-600">(매장 진열)</span>}
              </td>
              <td className="py-2 pr-4 text-right tabular-nums font-medium">{fmt(r.qty)}</td>
            </tr>
          ))}
          {stock.data?.length === 0 && (
            <tr><td colSpan={5} className="py-8 text-center text-stone-400">보관 중인 재고가 없습니다</td></tr>
          )}
        </Table>
      </Card>

      <Card title="정산서">
        <Table head={['기간', '종류', '버전', '당기 발생', 'MG 차감', '기말 잔액']}>
          {statements.data?.map((s) => (
            <tr key={s.id} className={s.latest ? '' : 'opacity-50'}>
              <td className="py-2 pr-4">
                <Link to={`/portal/statements/${s.id}`} className="text-emerald-700 hover:underline font-medium">
                  {s.yearMonth}
                </Link>
              </td>
              <td className="py-2 pr-4">{s.kind === 'LABEL_CONSIGN' ? '위탁 정산' : '매입 정산'}</td>
              <td className="py-2 pr-4">v{s.version}</td>
              <td className="py-2 pr-4 text-right"><Money value={s.chargeTotal} /></td>
              <td className="py-2 pr-4 text-right"><Money value={s.advanceTotal} /></td>
              <td className="py-2 pr-4 text-right font-medium"><Money value={s.closingBalance} /></td>
            </tr>
          ))}
          {statements.data?.length === 0 && (
            <tr><td colSpan={6} className="py-8 text-center text-stone-400">발행된 정산서가 없습니다</td></tr>
          )}
        </Table>
      </Card>

      <Card title="최근 거래 내역">
        <Table head={['일자', '구분', '품목', '수량', '금액', '정산월']}>
          {ledger.data?.slice(0, 50).map((r, i) => (
            <tr key={i} className={r.reversal ? 'text-stone-400' : ''}>
              <td className="py-2 pr-4">{r.occurredOn}</td>
              <td className="py-2 pr-4">
                {ENTRY_LABEL[r.entryType] ?? r.entryType}
                {r.reversal && <span className="text-xs ml-1">(취소분)</span>}
              </td>
              <td className="py-2 pr-4">{r.skuName ?? '—'}</td>
              <td className="py-2 pr-4 text-right tabular-nums">{r.qty != null ? fmt(r.qty) : '—'}</td>
              <td className="py-2 pr-4 text-right"><Money value={r.amount} /></td>
              <td className="py-2 pr-4 text-stone-400">{r.yearMonth ?? '미마감'}</td>
            </tr>
          ))}
          {ledger.data?.length === 0 && (
            <tr><td colSpan={6} className="py-8 text-center text-stone-400">거래 내역이 없습니다</td></tr>
          )}
        </Table>
      </Card>
    </div>
  )
}

function PortalStatementPage() {
  const { id } = useParams()
  const st = useQuery({
    queryKey: ['portal-statement', id],
    queryFn: () => api.get<PortalStatementDetail>(`/api/portal/statements/${id}`),
  })

  if (st.isLoading) return <p className="text-stone-400">불러오는 중…</p>
  if (!st.data) return <p className="text-stone-400">정산서가 없습니다</p>
  const { header: h, lines } = st.data

  function saveCsv() {
    const rows: (string | number | null | undefined)[][] = lines.map((l) => [
      ENTRY_LABEL[l.entryType] ?? l.entryType, l.label, l.qty, l.supplyAmount, l.vatAmount, l.amount,
    ])
    rows.push(
      [],
      ['이월 잔액', '', '', '', '', h.openingBalance],
      ['입금/지급', '', '', '', '', h.paymentTotal],
      ...(h.advanceTotal !== 0 ? [['MG 차감', '', '', '', '', h.advanceTotal] as (string | number)[]] : []),
      ['기말 잔액', '', '', '', '', h.closingBalance],
    )
    downloadCsv(`정산서_${h.yearMonth}_v${h.version}.csv`,
      ['구분', '품목', '수량', '공급가액', '부가세', '합계'], rows)
  }

  return (
    <div className="space-y-4">
      <Link to="/portal" className="text-sm text-emerald-700 hover:underline print:hidden">← 돌아가기</Link>
      <div className="flex items-start justify-between flex-wrap gap-2">
        <h1 className="text-xl font-bold text-stone-900 flex items-center gap-3">
          <span>
            {h.yearMonth} {h.kind === 'LABEL_CONSIGN' ? '위탁 정산서' : '매입 정산서'}
            <span className="ml-2 text-sm font-normal text-stone-500">v{h.version}{!h.latest && ' (구버전)'}</span>
          </span>
          {h.latest && <Stamp>정산 확정</Stamp>}
        </h1>
        <div className="flex gap-2 print:hidden">
          <Button variant="ghost" onClick={saveCsv}>엑셀 저장</Button>
          <Button variant="ghost" onClick={() => window.print()}>인쇄</Button>
        </div>
      </div>

      <Card>
        <div className={`grid grid-cols-2 gap-3 text-sm ${h.advanceTotal !== 0 ? 'sm:grid-cols-6' : 'sm:grid-cols-5'}`}>
          <PSum label="이월 잔액" value={h.openingBalance} />
          <PSum label="당기 공급가액" value={h.chargeSupply} />
          <PSum label="부가세" value={h.chargeVat} />
          <PSum label="입금/지급" value={h.paymentTotal} />
          {h.advanceTotal !== 0 && <PSum label="MG 차감" value={h.advanceTotal} />}
          <PSum label="기말 잔액" value={h.closingBalance} strong />
        </div>
        <p className="text-xs text-stone-400 mt-3">음수 = 유통사가 지급할 금액</p>
      </Card>

      <Card title="명세">
        <Table head={['구분', '품목', '수량', '공급가액', '부가세', '합계']}>
          {lines.map((l, i) => (
            <tr key={i}>
              <td className="py-2 pr-4">{ENTRY_LABEL[l.entryType] ?? l.entryType}</td>
              <td className="py-2 pr-4">{l.label}</td>
              <td className="py-2 pr-4 text-right tabular-nums">{l.qty != null ? fmt(l.qty) : '—'}</td>
              <td className="py-2 pr-4 text-right"><Money value={l.supplyAmount} /></td>
              <td className="py-2 pr-4 text-right"><Money value={l.vatAmount} /></td>
              <td className="py-2 pr-4 text-right font-medium"><Money value={l.amount} /></td>
            </tr>
          ))}
        </Table>
      </Card>
    </div>
  )
}

function PSum({ label, value, strong }: { label: string; value: number; strong?: boolean }) {
  return (
    <div>
      <p className="text-xs text-stone-400">{label}</p>
      <p className={`tabular-nums ${strong ? 'font-bold text-base' : ''} ${value < 0 ? 'text-red-600' : 'text-stone-800'}`}>
        {value.toLocaleString('ko-KR')}원
      </p>
    </div>
  )
}
