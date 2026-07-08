import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, fmt, type Location, type StockRow } from '../api'
import { Button, Card, Select, Table } from '../ui'
import { downloadCsv } from '../csv'

export default function Stock() {
  const [locationId, setLocationId] = useState('')
  const locations = useQuery({ queryKey: ['locations'], queryFn: () => api.get<Location[]>('/api/locations') })
  const rows = useQuery({
    queryKey: ['stock', locationId],
    queryFn: () => api.get<StockRow[]>(`/api/reports/stock${locationId ? `?locationId=${locationId}` : ''}`),
  })

  const total = (rows.data ?? []).reduce((s, r) => s + r.qty, 0)

  function saveCsv() {
    downloadCsv(`재고현황_${new Date().toISOString().slice(0, 10)}.csv`,
      ['앨범', '버전', 'SKU', '바코드', '위치', '소유', '수량'],
      (rows.data ?? []).map((r) => [
        r.albumTitle, r.versionName, r.skuName, r.barcode,
        r.locationName + (r.locationKind === 'RETAILER' ? ' (매장)' : ''),
        r.ownerPartyId != null ? `위탁: ${r.ownerName}` : '자사',
        r.qty,
      ]))
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <h1 className="text-xl font-bold text-stone-900">재고 현황</h1>
        <div className="flex gap-2 items-center">
          <div className="w-48">
            <Select value={locationId} onChange={(e) => setLocationId(e.target.value)}>
              <option value="">전체 위치</option>
              {locations.data?.map((l) => (
                <option key={l.id} value={l.id}>{l.name}{l.kind === 'RETAILER' ? ' (매장)' : ''}</option>
              ))}
            </Select>
          </div>
          <Button variant="ghost" disabled={!rows.data?.length} onClick={saveCsv}>엑셀 저장</Button>
        </div>
      </div>
      <Card>
        <Table head={['앨범', '버전', 'SKU', '바코드', '위치', '소유', '수량']}>
          {rows.data?.map((r, i) => (
            <tr key={i}>
              <td className="py-2 pr-4">{r.albumTitle}</td>
              <td className="py-2 pr-4">{r.versionName}</td>
              <td className="py-2 pr-4">{r.skuName}</td>
              <td className="py-2 pr-4 text-stone-400 font-mono text-xs">{r.barcode}</td>
              <td className="py-2 pr-4">
                {r.locationName}
                {r.locationKind === 'RETAILER' && (
                  <span className="ml-1 text-xs text-amber-600">(미판매·회수 대상)</span>
                )}
              </td>
              <td className="py-2 pr-4">
                {r.ownerPartyId != null
                  ? <span className="text-xs text-amber-800 bg-amber-50 border border-amber-300 rounded px-1.5 py-0.5">위탁: {r.ownerName}</span>
                  : <span className="text-xs text-stone-400">자사</span>}
              </td>
              <td className="py-2 pr-4 text-right tabular-nums font-medium">{fmt(r.qty)}</td>
            </tr>
          ))}
          {rows.data?.length === 0 && (
            <tr><td colSpan={7} className="py-8 text-center text-stone-400">재고가 없습니다</td></tr>
          )}
        </Table>
        {(rows.data?.length ?? 0) > 0 && (
          <p className="text-right text-sm text-stone-500 mt-2">합계 {fmt(total)}개</p>
        )}
      </Card>
    </div>
  )
}
