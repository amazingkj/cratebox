import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, type Org } from '../api'
import { Button, Card, Field, Input } from '../ui'

export default function Settings() {
  return (
    <div className="space-y-4 max-w-3xl">
      <h1 className="text-xl font-bold text-stone-900">설정</h1>
      <OrgProfileCard />
      <PasswordCard />
    </div>
  )
}

/** 발행사(회사) 정보 — 정산서 머리글·인쇄물에 표기된다 */
function OrgProfileCard() {
  const qc = useQueryClient()
  const org = useQuery({ queryKey: ['org'], queryFn: () => api.get<Org>('/api/org') })
  const [form, setForm] = useState<Org>({ name: '' })
  const [msg, setMsg] = useState('')

  useEffect(() => {
    if (org.data) setForm(org.data)
  }, [org.data])

  const save = useMutation({
    mutationFn: () => api.put<Org>('/api/org', form),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['org'] })
      setMsg('저장되었습니다')
    },
    onError: (e) => setMsg(e instanceof ApiError ? e.message : '저장 실패'),
  })

  const set = (k: keyof Org) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm({ ...form, [k]: e.target.value })

  return (
    <Card title="회사 정보">
      <p className="text-xs text-stone-400 mb-3">여기 입력한 정보가 정산서 머리글과 인쇄물에 발행사로 표기됩니다.</p>
      <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
        <Field label="회사명"><Input value={form.name} onChange={set('name')} /></Field>
        <Field label="사업자번호"><Input value={form.bizRegNo ?? ''} onChange={set('bizRegNo')} placeholder="000-00-00000" /></Field>
        <Field label="대표자"><Input value={form.ceoName ?? ''} onChange={set('ceoName')} /></Field>
        <Field label="연락처"><Input value={form.phone ?? ''} onChange={set('phone')} /></Field>
        <Field label="이메일"><Input value={form.email ?? ''} onChange={set('email')} /></Field>
        <Field label="주소"><Input value={form.address ?? ''} onChange={set('address')} /></Field>
      </div>
      {msg && <p className={`text-sm mt-2 ${msg === '저장되었습니다' ? 'text-emerald-700' : 'text-red-600'}`}>{msg}</p>}
      <div className="mt-3">
        <Button disabled={!form.name || save.isPending} onClick={() => save.mutate()}>저장</Button>
      </div>
    </Card>
  )
}

/** 내 비밀번호 변경 (현재 비밀번호 확인 후) */
export function PasswordCard() {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [msg, setMsg] = useState('')

  const change = useMutation({
    mutationFn: () => api.post('/api/auth/password', { currentPassword: current, newPassword: next }),
    onSuccess: () => {
      setCurrent('')
      setNext('')
      setConfirm('')
      setMsg('비밀번호가 변경되었습니다')
    },
    onError: (e) => setMsg(e instanceof ApiError ? e.message : '변경 실패'),
  })

  const mismatch = confirm !== '' && next !== confirm

  return (
    <Card title="비밀번호 변경">
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
        <Field label="현재 비밀번호">
          <Input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} />
        </Field>
        <Field label="새 비밀번호 (8자 이상)">
          <Input type="password" value={next} onChange={(e) => setNext(e.target.value)} />
        </Field>
        <Field label="새 비밀번호 확인">
          <Input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        </Field>
      </div>
      {mismatch && <p className="text-sm text-red-600 mt-2">새 비밀번호가 서로 다릅니다</p>}
      {msg && <p className={`text-sm mt-2 ${msg.includes('변경되었습니다') ? 'text-emerald-700' : 'text-red-600'}`}>{msg}</p>}
      <div className="mt-3">
        <Button variant="ghost"
                disabled={!current || next.length < 8 || next !== confirm || change.isPending}
                onClick={() => change.mutate()}>
          비밀번호 변경
        </Button>
      </div>
    </Card>
  )
}
