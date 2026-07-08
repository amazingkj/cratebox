import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, type Party, type Payment } from '../api'
import { Button, Card, Field, Input, Money, Select, Table } from '../ui'

export default function Payments() {
  const qc = useQueryClient()
  const [error, setError] = useState('')
  const [form, setForm] = useState({
    counterpartyId: '', direction: 'IN', amount: '',
    occurredOn: new Date().toISOString().slice(0, 10), memo: '',
  })

  const parties = useQuery({ queryKey: ['parties'], queryFn: () => api.get<Party[]>('/api/parties') })
  const payments = useQuery({ queryKey: ['payments'], queryFn: () => api.get<Payment[]>('/api/settlement/payments') })

  const create = useMutation({
    mutationFn: () => api.post('/api/settlement/payments', {
      counterpartyId: Number(form.counterpartyId),
      direction: form.direction,
      amount: Number(form.amount),
      occurredOn: form.occurredOn,
      memo: form.memo || null,
    }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['payments'] })
      setForm({ ...form, amount: '', memo: '' })
      setError('')
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : '저장 실패'),
  })

  const reverse = useMutation({
    mutationFn: (id: number) => api.post(`/api/settlement/payments/${id}/reverse`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['payments'] }),
    onError: (e) => setError(e instanceof ApiError ? e.message : '취소 실패'),
  })

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold text-gray-900">입금·지급</h1>

      <Card title="기록">
        <div className="grid grid-cols-2 sm:grid-cols-6 gap-3 items-end">
          <Field label="구분">
            <Select value={form.direction} onChange={(e) => setForm({ ...form, direction: e.target.value })}>
              <option value="IN">입금 (거래처→우리)</option>
              <option value="OUT">지급 (우리→기획사)</option>
            </Select>
          </Field>
          <Field label="상대방">
            <Select value={form.counterpartyId} onChange={(e) => setForm({ ...form, counterpartyId: e.target.value })}>
              <option value="">선택…</option>
              {parties.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </Select>
          </Field>
          <Field label="금액(원)">
            <Input type="number" min="1" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
          </Field>
          <Field label="일자">
            <Input type="date" value={form.occurredOn} onChange={(e) => setForm({ ...form, occurredOn: e.target.value })} />
          </Field>
          <Field label="메모">
            <Input value={form.memo} onChange={(e) => setForm({ ...form, memo: e.target.value })} />
          </Field>
          <Button disabled={!form.counterpartyId || !form.amount || create.isPending} onClick={() => create.mutate()}>
            기록
          </Button>
        </div>
        {error && <p className="text-sm text-red-600 mt-2">{error}</p>}
      </Card>

      <Card>
        <Table head={['일자', '구분', '상대방', '금액', '메모', '']}>
          {payments.data?.map((p) => (
            <tr key={p.id} className={p.reversed ? 'opacity-40 line-through' : ''}>
              <td className="py-2 pr-4">{p.occurredOn}</td>
              <td className="py-2 pr-4">{p.direction === 'IN' ? '입금' : '지급'}</td>
              <td className="py-2 pr-4">{p.counterpartyName}</td>
              <td className="py-2 pr-4 text-right"><Money value={p.amount} /></td>
              <td className="py-2 pr-4 text-gray-500">{p.memo ?? '—'}</td>
              <td className="py-2 pr-4 text-right">
                {!p.reversed && (
                  <button className="text-xs text-red-500 hover:underline"
                          onClick={() => { if (confirm('이 건을 취소(역분개)할까요?')) reverse.mutate(p.id) }}>
                    취소
                  </button>
                )}
              </td>
            </tr>
          ))}
          {payments.data?.length === 0 && (
            <tr><td colSpan={6} className="py-8 text-center text-gray-400">기록이 없습니다</td></tr>
          )}
        </Table>
      </Card>
    </div>
  )
}
