import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, DOC_TYPE_LABEL, fmt, type SkuFlat, type StockDoc } from '../api'
import { Badge, Button, Card, Table } from '../ui'

export default function DocDetail() {
  const { id } = useParams()
  const nav = useNavigate()
  const qc = useQueryClient()
  const [error, setError] = useState('')

  const doc = useQuery({ queryKey: ['doc', id], queryFn: () => api.get<StockDoc>(`/api/docs/${id}`) })
  const skus = useQuery({ queryKey: ['skus'], queryFn: () => api.get<SkuFlat[]>('/api/skus') })

  const act = useMutation({
    mutationFn: async (action: 'post' | 'reverse' | 'delete') => {
      if (action === 'delete') {
        await api.del(`/api/docs/${id}`)
        return null
      }
      return api.post<StockDoc>(`/api/docs/${id}/${action}`, action === 'reverse' ? {} : undefined)
    },
    onSuccess: (res, action) => {
      qc.invalidateQueries()
      if (action === 'delete') nav('/docs')
      else if (action === 'reverse' && res) nav(`/docs/${res.id}`)
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : '처리 실패'),
  })

  if (doc.isLoading) return <p className="text-stone-400">불러오는 중…</p>
  if (!doc.data) return <p className="text-stone-400">문서가 없습니다</p>
  const d = doc.data
  const skuName = (skuId: number) => {
    const s = skus.data?.find((x) => x.id === skuId)
    return s ? `${s.albumTitle} · ${s.versionName} · ${s.name}` : `SKU #${skuId}`
  }
  const priced = d.lines.some((l) => l.unitPrice != null)
  const totalSupply = d.lines.reduce((sum, l) => sum + (l.unitPrice ?? 0) * l.qty, 0)
  // 소유 구분: 위탁 라인의 소유자는 항상 해당 앨범의 기획사 (전기 규칙)
  const hasOwner = d.lines.some((l) => l.ownerPartyId != null)
  const ownerLabel = (l: { skuId: number; ownerPartyId?: number | null }) =>
    l.ownerPartyId == null ? '자사' : `위탁: ${skus.data?.find((s) => s.id === l.skuId)?.labelName ?? '기획사'}`

  return (
    <div className="space-y-4 max-w-3xl">
      <div className="flex items-center justify-between flex-wrap gap-2">
        <div className="flex items-center gap-3">
          <h1 className="text-xl font-bold text-stone-900 font-mono tracking-tight">{d.docNo}</h1>
          <Badge status={d.status} />
        </div>
        <div className="flex gap-2">
          {d.status === 'DRAFT' && (
            <>
              <Button variant="danger" disabled={act.isPending} onClick={() => act.mutate('delete')}>삭제</Button>
              <Button disabled={act.isPending} onClick={() => act.mutate('post')}>확정</Button>
            </>
          )}
          {d.status === 'POSTED' && !d.reversalOfDocId && (
            <Button variant="danger" disabled={act.isPending}
                    onClick={() => { if (confirm('확정된 문서는 지우는 대신 반대 방향의 취소 문서가 만들어져 기록이 남습니다. 취소할까요?')) act.mutate('reverse') }}>
              확정 취소
            </Button>
          )}
        </div>
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}
      {d.reversalOfDocId && (
        <p className="text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded px-3 py-2">
          이 문서는 <Link className="underline" to={`/docs/${d.reversalOfDocId}`}>원본 문서</Link>를 취소하기 위해 만들어진 문서입니다
        </p>
      )}
      {d.reversedByDocId && (
        <p className="text-sm text-stone-600 bg-stone-100 border border-stone-200 rounded px-3 py-2">
          이 문서는 <Link className="underline" to={`/docs/${d.reversedByDocId}`}>취소 문서</Link>로 취소되었습니다
        </p>
      )}

      <Card>
        <dl className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
          <div><dt className="text-stone-400 text-xs">유형</dt><dd>{DOC_TYPE_LABEL[d.docType]}</dd></div>
          <div><dt className="text-stone-400 text-xs">일자</dt><dd>{d.occurredOn}</dd></div>
          <div><dt className="text-stone-400 text-xs">확정 시각</dt><dd>{d.postedAt ? new Date(d.postedAt).toLocaleString('ko-KR') : '—'}</dd></div>
          <div><dt className="text-stone-400 text-xs">메모</dt><dd>{d.memo ?? '—'}</dd></div>
        </dl>
      </Card>

      <Card title="품목">
        <Table head={[
          '품목',
          ...(hasOwner ? ['재고 구분'] : []),
          '수량',
          ...(priced ? ['단가', '공급가액'] : []),
        ]}>
          {d.lines.map((l) => (
            <tr key={l.id}>
              <td className="py-2 pr-4">{skuName(l.skuId)}</td>
              {hasOwner && (
                <td className="py-2 pr-4">
                  {l.ownerPartyId != null
                    ? <span className="text-xs text-amber-800 bg-amber-50 border border-amber-300 rounded px-1.5 py-0.5">{ownerLabel(l)}</span>
                    : <span className="text-xs text-stone-400">자사</span>}
                </td>
              )}
              <td className="py-2 pr-4 text-right tabular-nums">{fmt(l.qty)}</td>
              {priced && <td className="py-2 pr-4 text-right tabular-nums">{fmt(l.unitPrice)}</td>}
              {priced && <td className="py-2 pr-4 text-right tabular-nums">{fmt((l.unitPrice ?? 0) * l.qty)}</td>}
            </tr>
          ))}
        </Table>
        {priced && (
          <p className="text-right text-sm mt-2 text-stone-600">
            공급가액 합계 <b className="tabular-nums">{fmt(totalSupply)}</b> (+VAT {fmt(Math.trunc(Math.abs(totalSupply) / 10) * Math.sign(totalSupply))})
          </p>
        )}
      </Card>
    </div>
  )
}
