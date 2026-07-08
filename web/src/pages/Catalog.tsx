import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, fmt, type Agreement, type Album, type AlbumVersion, type Party, type Sku } from '../api'
import { Button, Card, Field, Input, Select } from '../ui'

export default function Catalog() {
  const qc = useQueryClient()
  const [selectedAlbum, setSelectedAlbum] = useState<number | null>(null)
  const [error, setError] = useState('')

  const albums = useQuery({ queryKey: ['albums'], queryFn: () => api.get<Album[]>('/api/albums') })
  const labels = useQuery({ queryKey: ['parties', 'LABEL'], queryFn: () => api.get<Party[]>('/api/parties?kind=LABEL') })

  const [albumForm, setAlbumForm] = useState({ labelPartyId: '', title: '', artistName: '', releaseDate: '' })
  const addAlbum = useMutation({
    mutationFn: () => api.post<Album>('/api/albums', {
      labelPartyId: Number(albumForm.labelPartyId),
      title: albumForm.title, artistName: albumForm.artistName,
      releaseDate: albumForm.releaseDate || null,
    }),
    onSuccess: (a) => {
      qc.invalidateQueries({ queryKey: ['albums'] })
      setAlbumForm({ labelPartyId: '', title: '', artistName: '', releaseDate: '' })
      setSelectedAlbum(a.id)
      setError('')
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : '저장 실패'),
  })

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold text-gray-900">상품 관리</h1>
      {error && <p className="text-sm text-red-600">{error}</p>}

      <Card title="앨범 등록">
        <div className="grid grid-cols-2 sm:grid-cols-5 gap-3 items-end">
          <Field label="기획사">
            <Select value={albumForm.labelPartyId} onChange={(e) => setAlbumForm({ ...albumForm, labelPartyId: e.target.value })}>
              <option value="">선택…</option>
              {labels.data?.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
            </Select>
          </Field>
          <Field label="앨범명"><Input value={albumForm.title} onChange={(e) => setAlbumForm({ ...albumForm, title: e.target.value })} /></Field>
          <Field label="아티스트"><Input value={albumForm.artistName} onChange={(e) => setAlbumForm({ ...albumForm, artistName: e.target.value })} /></Field>
          <Field label="발매일"><Input type="date" value={albumForm.releaseDate} onChange={(e) => setAlbumForm({ ...albumForm, releaseDate: e.target.value })} /></Field>
          <Button disabled={!albumForm.labelPartyId || !albumForm.title || !albumForm.artistName || addAlbum.isPending}
                  onClick={() => addAlbum.mutate()}>앨범 추가</Button>
        </div>
      </Card>

      <div className="grid md:grid-cols-2 gap-4">
        <Card title="앨범">
          <ul className="divide-y divide-gray-100 text-sm">
            {albums.data?.map((a) => (
              <li key={a.id}
                  className={`py-2 px-2 rounded cursor-pointer ${selectedAlbum === a.id ? 'bg-indigo-50' : 'hover:bg-gray-50'}`}
                  onClick={() => setSelectedAlbum(a.id)}>
                <span className="font-medium">{a.title}</span>
                <span className="text-gray-500"> — {a.artistName}</span>
                {a.releaseDate && <span className="text-xs text-gray-400 ml-2">{a.releaseDate}</span>}
              </li>
            ))}
            {albums.data?.length === 0 && <li className="py-6 text-center text-gray-400">앨범이 없습니다</li>}
          </ul>
        </Card>
        {selectedAlbum != null && <VersionPanel albumId={selectedAlbum} onError={setError} />}
      </div>
    </div>
  )
}

function VersionPanel({ albumId, onError }: { albumId: number; onError: (m: string) => void }) {
  const qc = useQueryClient()
  const versions = useQuery({
    queryKey: ['versions', albumId],
    queryFn: () => api.get<AlbumVersion[]>(`/api/albums/${albumId}/versions`),
  })
  const [name, setName] = useState('')
  const [releaseDate, setReleaseDate] = useState('')

  const addVersion = useMutation({
    mutationFn: () => api.post<AlbumVersion>(`/api/albums/${albumId}/versions`, { name, releaseDate: releaseDate || null }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['versions', albumId] })
      setName('')
      setReleaseDate('')
    },
    onError: (e) => onError(e instanceof ApiError ? e.message : '저장 실패'),
  })

  return (
    <Card title="버전 · SKU">
      <AgreementRow albumId={albumId} onError={onError} />
      <div className="flex gap-2 items-end mb-3">
        <Field label="버전명 (예: A ver.)"><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="발매일(버전별)"><Input type="date" value={releaseDate} onChange={(e) => setReleaseDate(e.target.value)} /></Field>
        <Button variant="ghost" disabled={!name || addVersion.isPending} onClick={() => addVersion.mutate()}>추가</Button>
      </div>
      <div className="space-y-3">
        {versions.data?.map((v) => <SkuPanel key={v.id} version={v} onError={onError} />)}
      </div>
    </Card>
  )
}

/** 앨범 단위 거래 계약: 사입 또는 위탁(수수료율). 위탁 재고를 다루기 위한 전제 조건 */
function AgreementRow({ albumId, onError }: { albumId: number; onError: (m: string) => void }) {
  const qc = useQueryClient()
  const agreements = useQuery({ queryKey: ['agreements'], queryFn: () => api.get<Agreement[]>('/api/agreements') })
  const current = agreements.data?.find((a) => a.albumId === albumId)
  const [kind, setKind] = useState('')
  const [ratePct, setRatePct] = useState('')

  useEffect(() => {
    setKind(current?.kind ?? '')
    setRatePct(current?.commissionRate != null ? String(Math.round(current.commissionRate * 10000) / 100) : '')
  }, [albumId, current?.kind, current?.commissionRate])

  const save = useMutation({
    mutationFn: async () => {
      if (!kind) {
        if (current) await api.del(`/api/albums/${albumId}/agreement`)
        return
      }
      await api.put(`/api/albums/${albumId}/agreement`, {
        kind,
        commissionRate: kind === 'CONSIGNMENT' ? Number(ratePct) / 100 : null,
      })
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['agreements'] })
      qc.invalidateQueries({ queryKey: ['skus'] })
      onError('')
    },
    onError: (e) => onError(e instanceof ApiError ? e.message : '저장 실패'),
  })

  return (
    <div className="flex gap-2 items-end mb-3 pb-3 border-b border-gray-100 flex-wrap">
      <Field label="거래 계약">
        <Select value={kind} onChange={(e) => setKind(e.target.value)}>
          <option value="">미지정</option>
          <option value="PURCHASE">사입</option>
          <option value="CONSIGNMENT">위탁</option>
        </Select>
      </Field>
      {kind === 'CONSIGNMENT' && (
        <Field label="수수료율 (%)">
          <Input type="number" min={0} max={100} step="0.01" value={ratePct}
                 onChange={(e) => setRatePct(e.target.value)} placeholder="예: 15" />
        </Field>
      )}
      <Button variant="ghost"
              disabled={save.isPending || (kind === 'CONSIGNMENT' && ratePct === '')}
              onClick={() => save.mutate()}>
        계약 저장
      </Button>
      <span className="text-xs text-gray-400 pb-2">
        {current
          ? current.kind === 'CONSIGNMENT'
            ? `현재: 위탁 · 수수료 ${Math.round((current.commissionRate ?? 0) * 10000) / 100}%`
            : '현재: 사입'
          : '위탁 재고(수탁입고)를 다루려면 위탁 계약이 필요합니다'}
      </span>
    </div>
  )
}

function SkuPanel({ version, onError }: { version: AlbumVersion; onError: (m: string) => void }) {
  const qc = useQueryClient()
  const skus = useQuery({
    queryKey: ['skus', version.id],
    queryFn: () => api.get<Sku[]>(`/api/versions/${version.id}/skus`),
  })
  const [form, setForm] = useState({ barcode: '', name: '', listPrice: '' })

  const addSku = useMutation({
    mutationFn: () => api.post<Sku>(`/api/versions/${version.id}/skus`, {
      barcode: form.barcode, name: form.name, listPrice: Number(form.listPrice),
    }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['skus', version.id] })
      qc.invalidateQueries({ queryKey: ['skus'] })
      setForm({ barcode: '', name: '', listPrice: '' })
    },
    onError: (e) => onError(e instanceof ApiError ? e.message : '저장 실패'),
  })

  return (
    <div className="border border-gray-200 rounded p-3">
      <p className="text-sm font-medium mb-2">
        {version.name}
        {version.releaseDate && <span className="text-xs text-gray-400 ml-2">발매 {version.releaseDate}</span>}
      </p>
      <ul className="text-sm space-y-1 mb-2">
        {skus.data?.map((s) => (
          <li key={s.id} className="flex justify-between text-gray-600">
            <span>{s.name} <span className="text-gray-400 text-xs">{s.barcode}</span></span>
            <span className="tabular-nums">정가 {fmt(s.listPrice)}원</span>
          </li>
        ))}
      </ul>
      <div className="flex gap-2">
        <Input placeholder="바코드" value={form.barcode} onChange={(e) => setForm({ ...form, barcode: e.target.value })} />
        <Input placeholder="SKU명" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
        <Input placeholder="소비자가" type="number" value={form.listPrice} onChange={(e) => setForm({ ...form, listPrice: e.target.value })} />
        <Button variant="ghost" disabled={!form.barcode || !form.name || !form.listPrice || addSku.isPending}
                onClick={() => addSku.mutate()}>+</Button>
      </div>
    </div>
  )
}
