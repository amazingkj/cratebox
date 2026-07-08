import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, fmt, todayLocal, type Advance, type Album, type Party } from '../api'
import { Button, Card, Field, Input, Select, Table } from '../ui'

export default function Advances() {
  const qc = useQueryClient()
  const [error, setError] = useState('')
  const [form, setForm] = useState({
    labelPartyId: '', albumId: '', amount: '',
    paidOn: todayLocal(), memo: '',
  })

  const advances = useQuery({ queryKey: ['advances'], queryFn: () => api.get<Advance[]>('/api/advances') })
  const parties = useQuery({ queryKey: ['parties'], queryFn: () => api.get<Party[]>('/api/parties') })
  const albums = useQuery({ queryKey: ['albums'], queryFn: () => api.get<Album[]>('/api/albums') })

  const labels = (parties.data ?? []).filter((p) => p.kind === 'LABEL' && p.active)
  const labelAlbums = (albums.data ?? []).filter((a) => String(a.labelPartyId) === form.labelPartyId)

  const create = useMutation({
    mutationFn: () => api.post('/api/advances', {
      labelPartyId: Number(form.labelPartyId),
      albumId: Number(form.albumId),
      amount: Number(form.amount),
      paidOn: form.paidOn,
      memo: form.memo || null,
    }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['advances'] })
      setForm({ ...form, albumId: '', amount: '', memo: '' })
      setError('')
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : '저장 실패'),
  })

  const remove = useMutation({
    mutationFn: (id: number) => api.del(`/api/advances/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['advances'] })
      setError('')
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : '삭제 실패'),
  })

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold text-stone-900">MG 선급금</h1>
      <p className="text-sm text-stone-500">
        기획사에 지급한 MG(미니멈 개런티)를 기록합니다. 마감 시 해당 앨범의 위탁 정산액에서 자동
        차감(회수)되며, 별도의 지급으로 기록하지 마세요.
      </p>
      {error && <p className="text-sm text-red-600">{error}</p>}

      <Card title="선급금 등록">
        <div className="grid grid-cols-2 sm:grid-cols-6 gap-3 items-end">
          <Field label="기획사">
            <Select value={form.labelPartyId}
                    onChange={(e) => setForm({ ...form, labelPartyId: e.target.value, albumId: '' })}>
              <option value="">선택…</option>
              {labels.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
            </Select>
          </Field>
          <Field label="앨범 (위탁 계약 필요)">
            <Select value={form.albumId} onChange={(e) => setForm({ ...form, albumId: e.target.value })}>
              <option value="">{form.labelPartyId ? '선택…' : '기획사를 먼저 선택'}</option>
              {labelAlbums.map((a) => <option key={a.id} value={a.id}>{a.title}</option>)}
            </Select>
          </Field>
          <Field label="금액 (원)">
            <Input type="number" min={1} value={form.amount}
                   onChange={(e) => setForm({ ...form, amount: e.target.value })} />
          </Field>
          <Field label="지급일">
            <Input type="date" value={form.paidOn} onChange={(e) => setForm({ ...form, paidOn: e.target.value })} />
          </Field>
          <Field label="메모">
            <Input value={form.memo} onChange={(e) => setForm({ ...form, memo: e.target.value })} placeholder="선택" />
          </Field>
          <Button disabled={!form.labelPartyId || !form.albumId || !form.amount || create.isPending}
                  onClick={() => create.mutate()}>
            등록
          </Button>
        </div>
      </Card>

      <Card title="선급금 현황">
        <Table head={['지급일', '기획사', '앨범', '지급액', '회수', '잔여', '메모', '']}>
          {advances.data?.map((a) => (
            <tr key={a.id}>
              <td className="py-2 pr-4">{a.paidOn}</td>
              <td className="py-2 pr-4 font-medium">{a.labelName}</td>
              <td className="py-2 pr-4">{a.albumTitle}</td>
              <td className="py-2 pr-4 text-right tabular-nums">{fmt(a.amount)}</td>
              <td className="py-2 pr-4 text-right tabular-nums text-stone-500">{fmt(a.recouped)}</td>
              <td className={`py-2 pr-4 text-right tabular-nums font-medium ${a.remaining > 0 ? 'text-emerald-800' : 'text-stone-400'}`}>
                {fmt(a.remaining)}
              </td>
              <td className="py-2 pr-4 text-stone-500">{a.memo ?? ''}</td>
              <td className="py-2 pr-4 text-right">
                {a.recouped === 0 && (
                  <button className="text-stone-400 hover:text-red-500 text-sm"
                          onClick={() => { if (confirm('선급금 기록을 삭제할까요?')) remove.mutate(a.id) }}>
                    삭제
                  </button>
                )}
              </td>
            </tr>
          ))}
          {advances.data?.length === 0 && (
            <tr><td colSpan={8} className="py-8 text-center text-stone-400">등록된 선급금이 없습니다</td></tr>
          )}
        </Table>
      </Card>
    </div>
  )
}
