import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, type Party } from '../api'
import { Button, Card, Field, Input, Select, Table } from '../ui'

const empty = {
  kind: 'RETAILER' as 'LABEL' | 'RETAILER',
  name: '', bizRegNo: '', contactName: '', phone: '', email: '',
  settlementBasis: 'SELL_IN', defaultSupplyRate: '', lateReturnMode: 'CARRY_FORWARD',
}

export default function Parties() {
  const qc = useQueryClient()
  const [form, setForm] = useState(empty)
  const [editing, setEditing] = useState<number | null>(null)
  const [error, setError] = useState('')
  const parties = useQuery({ queryKey: ['parties'], queryFn: () => api.get<Party[]>('/api/parties') })

  const save = useMutation({
    mutationFn: () => {
      const body = {
        kind: form.kind,
        name: form.name,
        bizRegNo: form.bizRegNo || null,
        contactName: form.contactName || null,
        phone: form.phone || null,
        email: form.email || null,
        settlementBasis: form.kind === 'RETAILER' ? form.settlementBasis : null,
        defaultSupplyRate: form.defaultSupplyRate ? Number(form.defaultSupplyRate) : null,
        lateReturnMode: form.lateReturnMode,
      }
      return editing ? api.put<Party>(`/api/parties/${editing}`, body) : api.post<Party>('/api/parties', body)
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['parties'] })
      setForm(empty)
      setEditing(null)
      setError('')
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : '저장 실패'),
  })

  function edit(p: Party) {
    setEditing(p.id)
    setForm({
      kind: p.kind, name: p.name, bizRegNo: p.bizRegNo ?? '', contactName: p.contactName ?? '',
      phone: p.phone ?? '', email: p.email ?? '',
      settlementBasis: p.settlementBasis ?? 'SELL_IN',
      defaultSupplyRate: p.defaultSupplyRate != null ? String(p.defaultSupplyRate) : '',
      lateReturnMode: p.lateReturnMode,
    })
  }

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold text-stone-900">거래처·기획사</h1>

      <Card title={editing ? '수정' : '신규 등록'}>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
          <Field label="구분">
            <Select value={form.kind} disabled={editing != null}
                    onChange={(e) => setForm({ ...form, kind: e.target.value as 'LABEL' | 'RETAILER' })}>
              <option value="RETAILER">거래처</option>
              <option value="LABEL">기획사</option>
            </Select>
          </Field>
          <Field label="이름">
            <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </Field>
          {form.kind === 'RETAILER' && (
            <>
              <Field label="정산 기준">
                <Select value={form.settlementBasis} onChange={(e) => setForm({ ...form, settlementBasis: e.target.value })}>
                  <option value="SELL_IN">출고 기준</option>
                  <option value="SELL_THROUGH">판매분 기준</option>
                </Select>
              </Field>
              <Field label="기본 공급률 (0~1)">
                <Input type="number" step="0.01" min="0" max="1" placeholder="예: 0.65"
                       value={form.defaultSupplyRate}
                       onChange={(e) => setForm({ ...form, defaultSupplyRate: e.target.value })} />
              </Field>
              <Field label="마감후 반품 기본 처리">
                <Select value={form.lateReturnMode} onChange={(e) => setForm({ ...form, lateReturnMode: e.target.value })}>
                  <option value="CARRY_FORWARD">차기 이월</option>
                  <option value="RESTATE">소급 정정</option>
                </Select>
              </Field>
            </>
          )}
          <Field label="사업자번호"><Input value={form.bizRegNo} onChange={(e) => setForm({ ...form, bizRegNo: e.target.value })} /></Field>
          <Field label="담당자"><Input value={form.contactName} onChange={(e) => setForm({ ...form, contactName: e.target.value })} /></Field>
          <Field label="연락처"><Input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></Field>
        </div>
        {error && <p className="text-sm text-red-600 mt-2">{error}</p>}
        <div className="flex gap-2 mt-3">
          {editing && <Button variant="ghost" onClick={() => { setEditing(null); setForm(empty) }}>취소</Button>}
          <Button disabled={!form.name || save.isPending} onClick={() => save.mutate()}>
            {editing ? '수정 저장' : '등록'}
          </Button>
        </div>
        {editing && form.kind === 'LABEL' && <PortalAccount partyId={editing} onError={setError} />}
      </Card>

      <Card>
        <Table head={['이름', '구분', '정산 기준', '공급률', '마감후 반품', '담당자']}>
          {parties.data?.map((p) => (
            <tr key={p.id} className="hover:bg-stone-50 cursor-pointer" onClick={() => edit(p)}>
              <td className="py-2 pr-4 font-medium">{p.name}</td>
              <td className="py-2 pr-4">{p.kind === 'LABEL' ? '기획사' : '거래처'}</td>
              <td className="py-2 pr-4">
                {p.settlementBasis === 'SELL_IN' ? '출고 기준' : p.settlementBasis === 'SELL_THROUGH' ? '판매분 기준' : '—'}
              </td>
              <td className="py-2 pr-4 tabular-nums">{p.defaultSupplyRate ?? '—'}</td>
              <td className="py-2 pr-4">{p.kind === 'RETAILER' ? (p.lateReturnMode === 'RESTATE' ? '소급 정정' : '차기 이월') : '—'}</td>
              <td className="py-2 pr-4 text-stone-500">{p.contactName ?? '—'}</td>
            </tr>
          ))}
        </Table>
      </Card>
    </div>
  )
}

/** 기획사 포털 계정 발급 (기획사당 1개, 읽기전용 포털 로그인용) */
function PortalAccount({ partyId, onError }: { partyId: number; onError: (m: string) => void }) {
  const qc = useQueryClient()
  const info = useQuery({
    queryKey: ['portal-user', partyId],
    queryFn: () => api.get<{ exists: boolean; username?: string }>(`/api/parties/${partyId}/portal-user`),
  })
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')

  const create = useMutation({
    mutationFn: () => api.post(`/api/parties/${partyId}/portal-user`, { username, password }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['portal-user', partyId] })
      setUsername('')
      setPassword('')
      onError('')
    },
    onError: (e) => onError(e instanceof ApiError ? e.message : '발급 실패'),
  })

  return (
    <div className="mt-4 pt-3 border-t border-stone-100">
      <p className="text-sm font-medium mb-2">파트너 포털 계정</p>
      {info.data?.exists ? (
        <p className="text-sm text-stone-600">
          발급됨: <b>{info.data.username}</b>
          <span className="text-xs text-stone-400 ml-2">이 계정으로 로그인하면 자기 재고·정산서만 조회합니다</span>
        </p>
      ) : (
        <div className="flex gap-2 items-end flex-wrap">
          <Field label="아이디">
            <Input value={username} onChange={(e) => setUsername(e.target.value)} />
          </Field>
          <Field label="비밀번호 (8자 이상)">
            <Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
          </Field>
          <Button variant="ghost" disabled={!username || password.length < 8 || create.isPending}
                  onClick={() => create.mutate()}>
            포털 계정 발급
          </Button>
        </div>
      )}
    </div>
  )
}
