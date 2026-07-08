import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, type Period, type StatementSummary } from '../api'
import { Button, Card, Input, Money, Select, Table } from '../ui'

export default function Settlement() {
  const qc = useQueryClient()
  const [error, setError] = useState('')
  const [closeYm, setCloseYm] = useState('')
  const [filterYm, setFilterYm] = useState('')

  const periods = useQuery({ queryKey: ['periods'], queryFn: () => api.get<Period[]>('/api/settlement/periods') })
  const statements = useQuery({
    queryKey: ['statements', filterYm],
    queryFn: () => api.get<StatementSummary[]>(`/api/settlement/statements${filterYm ? `?yearMonth=${filterYm}` : ''}`),
  })

  const close = useMutation({
    mutationFn: () => api.post<{ yearMonth: string; statements: number }>('/api/settlement/close', { yearMonth: closeYm }),
    onSuccess: (r) => {
      qc.invalidateQueries()
      setError('')
      setFilterYm(r.yearMonth)
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : '마감 실패'),
  })

  const latestClosed = periods.data?.[0]?.yearMonth

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold text-stone-900">정산·마감</h1>

      <Card title="월 마감">
        <div className="flex items-end gap-2 flex-wrap">
          <div>
            <span className="block text-xs text-stone-500 mb-1">마감할 월 (YYYY-MM)</span>
            <Input type="month" value={closeYm} onChange={(e) => setCloseYm(e.target.value)} className="w-44" />
          </div>
          <Button disabled={!closeYm || close.isPending}
                  onClick={() => { if (confirm(`${closeYm} 을 마감합니다. 마감 후에는 되돌릴 수 없고, 해당 월까지 아직 정산되지 않은 거래가 정산서로 확정됩니다.`)) close.mutate() }}>
            마감 실행
          </Button>
          {latestClosed && <span className="text-sm text-stone-500 pb-1.5">현재 {latestClosed}까지 마감됨</span>}
        </div>
        {error && <p className="text-sm text-red-600 mt-2">{error}</p>}
        <p className="text-xs text-stone-400 mt-2">
          마감하면 해당 월까지 아직 정산되지 않은 거래를 모아 거래처·기획사별 정산서를 만듭니다.
          마감 후 들어온 반품은 자동으로 다음 마감에 이월되며, 문서 작성 시 '소급 정정'을 선택하면 직전 마감 정산서가 v+1로 재발행됩니다.
        </p>
      </Card>

      <Card
        title="정산서"
        actions={
          <div className="w-40">
            <Select value={filterYm} onChange={(e) => setFilterYm(e.target.value)}>
              <option value="">전체 기간</option>
              {periods.data?.map((p) => <option key={p.id} value={p.yearMonth}>{p.yearMonth}</option>)}
            </Select>
          </div>
        }
      >
        <Table head={['기간', '상대방', '종류', '버전', '이월 잔액', '당기 발생', '입금/지급', '기말 잔액']}>
          {statements.data?.map((s) => (
            <tr key={s.id} className={s.latest ? 'hover:bg-stone-50' : 'opacity-50'}>
              <td className="py-2 pr-4">{s.yearMonth}</td>
              <td className="py-2 pr-4">
                <Link to={`/settlement/statements/${s.id}`} className="text-emerald-700 hover:underline font-medium">
                  {s.counterpartyName}
                </Link>
              </td>
              <td className="py-2 pr-4">
                {s.kind === 'RETAILER' ? '거래처' : s.kind === 'LABEL_CONSIGN' ? '기획사 위탁' : '기획사 매입'}
              </td>
              <td className="py-2 pr-4">
                v{s.version}{s.latest && s.version > 1 && <span className="text-xs text-amber-600 ml-1">(재발행)</span>}
              </td>
              <td className="py-2 pr-4 text-right"><Money value={s.openingBalance} /></td>
              <td className="py-2 pr-4 text-right"><Money value={s.chargeTotal} /></td>
              <td className="py-2 pr-4 text-right"><Money value={s.paymentTotal} /></td>
              <td className="py-2 pr-4 text-right font-medium"><Money value={s.closingBalance} /></td>
            </tr>
          ))}
          {statements.data?.length === 0 && (
            <tr><td colSpan={8} className="py-8 text-center text-stone-400">정산서가 없습니다. 월 마감을 실행하세요.</td></tr>
          )}
        </Table>
      </Card>
    </div>
  )
}
