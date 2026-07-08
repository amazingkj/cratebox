import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, fmt, type StatementDetail } from '../api'
import { Button, Card, IssuerLine, Money, Stamp, Table } from '../ui'
import { downloadCsv } from '../csv'

const ENTRY_LABEL: Record<string, string> = {
  SALE: '판매', SALE_RETURN: '반품', PURCHASE: '매입', PURCHASE_RETURN: '매입반품',
  PAYMENT_IN: '입금', PAYMENT_OUT: '지급',
  CONSIGN_SALE: '위탁판매', CONSIGN_RETURN: '위탁반품', ADVANCE_RECOUP: 'MG 차감',
}

const KIND_LABEL: Record<string, string> = {
  RETAILER: '거래처 정산서', LABEL_PURCHASE: '기획사 매입 정산서', LABEL_CONSIGN: '기획사 위탁 정산서',
}

export default function StatementPage() {
  const { id } = useParams()
  const st = useQuery({
    queryKey: ['statement', id],
    queryFn: () => api.get<StatementDetail>(`/api/settlement/statements/${id}`),
  })

  if (st.isLoading) return <p className="text-stone-400">불러오는 중…</p>
  if (!st.data) return <p className="text-stone-400">정산서가 없습니다</p>
  const { header: h, issuer, lines } = st.data

  function saveCsv() {
    const rows: (string | number | null | undefined)[][] = lines.map((l) => [
      ENTRY_LABEL[l.entryType] ?? l.entryType, l.label, l.qty, l.unitPrice, l.supplyAmount, l.vatAmount, l.amount,
    ])
    rows.push(
      [],
      ['이월 잔액', '', '', '', '', '', h.openingBalance],
      ['입금/지급', '', '', '', '', '', h.paymentTotal],
      ...(h.advanceTotal !== 0 ? [['MG 차감', '', '', '', '', '', h.advanceTotal] as (string | number)[]] : []),
      ['기말 잔액', '', '', '', '', '', h.closingBalance],
    )
    downloadCsv(`정산서_${h.yearMonth}_${h.counterpartyName}_v${h.version}.csv`,
      ['구분', '품목', '수량', '단가', '공급가액', '부가세', '합계'], rows)
  }

  return (
    <div className="space-y-4 max-w-3xl">
      <div className="flex items-start justify-between flex-wrap gap-2">
        <div>
          <h1 className="text-xl font-bold text-stone-900 flex items-center gap-3">
            <span>
              {h.yearMonth} 정산서 — {h.counterpartyName}
              <span className="ml-2 text-sm font-normal text-stone-500">
                v{h.version}{!h.latest && ' (구버전)'}
              </span>
            </span>
            {h.latest && <Stamp>정산 확정</Stamp>}
          </h1>
          <p className="text-sm text-stone-500 mt-1">
            {KIND_LABEL[h.kind] ?? h.kind} ·
            발행 {new Date(h.generatedAt).toLocaleString('ko-KR')}
          </p>
          <IssuerLine issuer={issuer} />
        </div>
        <div className="flex gap-2 print:hidden">
          <Button variant="ghost" onClick={saveCsv}>엑셀 저장</Button>
          <Button variant="ghost" onClick={() => window.print()}>인쇄</Button>
        </div>
      </div>

      <Card>
        <div className={`grid grid-cols-2 gap-3 text-sm ${h.advanceTotal !== 0 ? 'sm:grid-cols-6' : 'sm:grid-cols-5'}`}>
          <Summary label="이월 잔액" value={h.openingBalance} />
          <Summary label="당기 공급가액" value={h.chargeSupply} />
          <Summary label="부가세" value={h.chargeVat} />
          <Summary label="입금/지급" value={h.paymentTotal} />
          {h.advanceTotal !== 0 && <Summary label="MG 차감" value={h.advanceTotal} />}
          <Summary label="기말 잔액" value={h.closingBalance} strong />
        </div>
        <p className="text-xs text-stone-400 mt-3">
          양수 = 받을 돈(미수), 음수 = 줄 돈(미지급)
          {h.advanceTotal !== 0 && ' · MG 차감 = 위탁 정산액에서 회수된 선급금'}
        </p>
      </Card>

      <Card title="명세">
        <Table head={['구분', '품목', '수량', '단가', '공급가액', '부가세', '합계']}>
          {lines.map((l, i) => (
            <tr key={i}>
              <td className="py-2 pr-4">{ENTRY_LABEL[l.entryType] ?? l.entryType}</td>
              <td className="py-2 pr-4">{l.label}</td>
              <td className="py-2 pr-4 text-right tabular-nums">{l.qty != null ? fmt(l.qty) : '—'}</td>
              <td className="py-2 pr-4 text-right tabular-nums">{l.unitPrice != null ? fmt(l.unitPrice) : '—'}</td>
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

function Summary({ label, value, strong }: { label: string; value: number; strong?: boolean }) {
  return (
    <div>
      <p className="text-xs text-stone-400">{label}</p>
      <p className={`tabular-nums ${strong ? 'font-bold text-base' : ''} ${value < 0 ? 'text-red-600' : 'text-stone-800'}`}>
        {value.toLocaleString('ko-KR')}원
      </p>
    </div>
  )
}
