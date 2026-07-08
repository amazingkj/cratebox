import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, DOC_TYPE_LABEL, fmt, type DocSummary, type DocType } from '../api'
import { Badge, Button, Card, Select, Table } from '../ui'

export default function Docs() {
  const [docType, setDocType] = useState('')
  const [status, setStatus] = useState('')
  const q = new URLSearchParams()
  if (docType) q.set('docType', docType)
  if (status) q.set('status', status)
  const docs = useQuery({
    queryKey: ['docs', docType, status],
    queryFn: () => api.get<DocSummary[]>(`/api/docs?${q.toString()}`),
  })

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <h1 className="text-xl font-bold text-gray-900">입출고 문서</h1>
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
            <option value="REVERSED">역분개됨</option>
          </Select>
        </div>
      </div>
      <Card>
        <Table head={['문서번호', '유형', '상태', '상대방', '위치', '일자', '수량']}>
          {docs.data?.map((d) => (
            <tr key={d.id} className="hover:bg-gray-50">
              <td className="py-2 pr-4">
                <Link to={`/docs/${d.id}`} className="text-indigo-600 hover:underline font-medium">
                  {d.docNo}
                </Link>
              </td>
              <td className="py-2 pr-4">{DOC_TYPE_LABEL[d.docType as DocType] ?? d.docType}</td>
              <td className="py-2 pr-4"><Badge status={d.status} /></td>
              <td className="py-2 pr-4">{d.counterpartyName ?? '—'}</td>
              <td className="py-2 pr-4 text-gray-500">
                {[d.locationFromName, d.locationToName].filter(Boolean).join(' → ') || '—'}
              </td>
              <td className="py-2 pr-4">{d.occurredOn}</td>
              <td className="py-2 pr-4 text-right tabular-nums">{fmt(d.totalQty)}</td>
            </tr>
          ))}
          {docs.data?.length === 0 && (
            <tr><td colSpan={7} className="py-8 text-center text-gray-400">문서가 없습니다</td></tr>
          )}
        </Table>
      </Card>
    </div>
  )
}
