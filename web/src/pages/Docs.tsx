import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, DOC_TYPE_LABEL, fmt, type DocSummary, type DocType, type Party } from '../api'
import { Badge, Button, Card, Input, Select, Table } from '../ui'

export default function Docs() {
  const [docType, setDocType] = useState('')
  const [status, setStatus] = useState('')
  const [cp, setCp] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  // 목록은 서버에서 최신 500건까지만 내려오므로 필터도 서버에서 적용해야 옛 문서가 검색된다
  const q = new URLSearchParams()
  if (docType) q.set('docType', docType)
  if (status) q.set('status', status)
  if (cp) q.set('counterpartyId', cp)
  if (from) q.set('from', from)
  if (to) q.set('to', to)
  const docs = useQuery({
    queryKey: ['docs', docType, status, cp, from, to],
    queryFn: () => api.get<DocSummary[]>(`/api/docs?${q.toString()}`),
  })
  const parties = useQuery({ queryKey: ['parties'], queryFn: () => api.get<Party[]>('/api/parties') })

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <h1 className="text-xl font-bold text-stone-900">입출고 문서</h1>
        <Link to="/docs/new"><Button>+ 새 문서</Button></Link>
      </div>
      <div className="flex gap-2 flex-wrap">
        <div className="w-40">
          <Select value={docType} onChange={(e) => setDocType(e.target.value)}>
            <option value="">모든 유형</option>
            {Object.entries(DOC_TYPE_LABEL).map(([k, v]) => (
              <option key={k} value={k}>{v}</option>
            ))}
          </Select>
        </div>
        <div className="w-32">
          <Select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">모든 상태</option>
            <option value="DRAFT">작성중</option>
            <option value="POSTED">확정</option>
            <option value="REVERSED">취소됨</option>
          </Select>
        </div>
        <div className="w-40">
          <Select value={cp} onChange={(e) => setCp(e.target.value)}>
            <option value="">모든 상대방</option>
            {parties.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </Select>
        </div>
        <div className="flex items-center gap-1">
          <Input type="date" className="w-36" value={from} onChange={(e) => setFrom(e.target.value)} />
          <span className="text-stone-400">~</span>
          <Input type="date" className="w-36" value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
      </div>
      <Card>
        <Table head={['문서번호', '유형', '상태', '상대방', '위치', '일자', '수량']}>
          {docs.data?.map((d) => (
            <tr key={d.id} className="hover:bg-stone-50">
              <td className="py-2 pr-4">
                <Link to={`/docs/${d.id}`} className="text-emerald-700 hover:underline font-medium font-mono text-[13px]">
                  {d.docNo}
                </Link>
              </td>
              <td className="py-2 pr-4">{DOC_TYPE_LABEL[d.docType as DocType] ?? d.docType}</td>
              <td className="py-2 pr-4"><Badge status={d.status} /></td>
              <td className="py-2 pr-4">{d.counterpartyName ?? '—'}</td>
              <td className="py-2 pr-4 text-stone-500">
                {[d.locationFromName, d.locationToName].filter(Boolean).join(' → ') || '—'}
              </td>
              <td className="py-2 pr-4">{d.occurredOn}</td>
              <td className="py-2 pr-4 text-right tabular-nums">{fmt(d.totalQty)}</td>
            </tr>
          ))}
          {docs.data?.length === 0 && (
            <tr><td colSpan={7} className="py-8 text-center text-stone-400">문서가 없습니다</td></tr>
          )}
        </Table>
      </Card>
    </div>
  )
}
