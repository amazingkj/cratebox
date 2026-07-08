import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, fmt, type Location, type StockRow } from '../api'
import { Card, Select, Table } from '../ui'

export default function Stock() {
  const [locationId, setLocationId] = useState('')
  const locations = useQuery({ queryKey: ['locations'], queryFn: () => api.get<Location[]>('/api/locations') })
  const rows = useQuery({
    queryKey: ['stock', locationId],
    queryFn: () => api.get<StockRow[]>(`/api/reports/stock${locationId ? `?locationId=${locationId}` : ''}`),
  })

  const total = (rows.data ?? []).reduce((s, r) => s + r.qty, 0)

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <h1 className="text-xl font-bold text-gray-900">재고 현황</h1>
        <div className="w-48">
          <Select value={locationId} onChange={(e) => setLocationId(e.target.value)}>
            <option value="">전체 위치</option>
            {locations.data?.map((l) => (
              <option key={l.id} value={l.id}>{l.name}{l.kind === 'RETAILER' ? ' (매장)' : ''}</option>
            ))}
          </Select>
        </div>
      </div>
      <Card>
        <Table head={['앨범', '버전', 'SKU', '바코드', '위치', '소유', '수량']}>
          {rows.data?.map((r, i) => (
            <tr key={i}>
              <td className="py-2 pr-4">{r.albumTitle}</td>
              <td className="py-2 pr-4">{r.versionName}</td>
              <td className="py-2 pr-4">{r.skuName}</td>
              <td className="py-2 pr-4 text-gray-400">{r.barcode}</td>
              <td className="py-2 pr-4">
                {r.locationName}
                {r.locationKind === 'RETAILER' && (
                  <span className="ml-1 text-xs text-amber-600">(미판매·회수 대상)</span>
                )}
              </td>
              <td className="py-2 pr-4">
                {r.ownerPartyId != null
                  ? <span className="text-xs text-indigo-700 bg-indigo-50 rounded px-1.5 py-0.5">위탁: {r.ownerName}</span>
                  : <span className="text-xs text-gray-400">자사</span>}
              </td>
              <td className="py-2 pr-4 text-right tabular-nums font-medium">{fmt(r.qty)}</td>
            </tr>
          ))}
          {rows.data?.length === 0 && (
            <tr><td colSpan={7} className="py-8 text-center text-gray-400">재고가 없습니다</td></tr>
          )}
        </Table>
        {(rows.data?.length ?? 0) > 0 && (
          <p className="text-right text-sm text-gray-500 mt-2">합계 {fmt(total)}개</p>
        )}
      </Card>
    </div>
  )
}
