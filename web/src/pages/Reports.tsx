import { useQuery } from '@tanstack/react-query'
import { api, fmt, type BalanceRow, type FirstWeekRow } from '../api'
import { Card, Money, Table } from '../ui'

export default function Reports() {
  const firstWeek = useQuery({ queryKey: ['first-week'], queryFn: () => api.get<FirstWeekRow[]>('/api/reports/first-week') })
  const balances = useQuery({ queryKey: ['balances'], queryFn: () => api.get<BalanceRow[]>('/api/reports/balances') })

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold text-stone-900">리포트</h1>

      <Card title="초동 (발매 후 7일 순출하량)">
        <p className="text-xs text-stone-400 mb-2">유통사 출고 기준 (판매출고 + 판매보고 − 거래처반품). 한터 등 POS 집계와 다릅니다.</p>
        <Table head={['앨범', '버전', '발매일', '집계 구간', '초동']}>
          {firstWeek.data?.map((r) => (
            <tr key={r.albumVersionId}>
              <td className="py-2 pr-4">{r.albumTitle}</td>
              <td className="py-2 pr-4">{r.versionName}</td>
              <td className="py-2 pr-4">{r.releaseDate}</td>
              <td className="py-2 pr-4 text-stone-500">{r.releaseDate} ~ {r.windowEnd}</td>
              <td className="py-2 pr-4 text-right font-medium tabular-nums">{fmt(r.units)}</td>
            </tr>
          ))}
          {firstWeek.data?.length === 0 && (
            <tr><td colSpan={5} className="py-8 text-center text-stone-400">발매일이 등록된 버전이 없습니다</td></tr>
          )}
        </Table>
      </Card>

      <Card title="상대방별 잔액">
        <p className="text-xs text-stone-400 mb-2">양수 = 받을 돈(미수), 음수 = 줄 돈(미지급). 미마감 = 아직 정산서에 확정되지 않은 금액.</p>
        <Table head={['상대방', '구분', '잔액', '미마감분']}>
          {balances.data?.map((b) => (
            <tr key={b.counterpartyId}>
              <td className="py-2 pr-4 font-medium">{b.name}</td>
              <td className="py-2 pr-4">{b.kind === 'LABEL' ? '기획사' : '거래처'}</td>
              <td className="py-2 pr-4 text-right"><Money value={b.balance} /></td>
              <td className="py-2 pr-4 text-right text-stone-500"><Money value={b.unstamped} /></td>
            </tr>
          ))}
          {balances.data?.length === 0 && (
            <tr><td colSpan={4} className="py-8 text-center text-stone-400">잔액이 없습니다</td></tr>
          )}
        </Table>
      </Card>
    </div>
  )
}
