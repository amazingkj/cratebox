import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api'
import { Button, Input } from '../ui'

export default function Login() {
  const nav = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      await api.post('/api/auth/login', { username, password })
      nav('/stock', { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '로그인 실패')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="min-h-screen grid place-items-center bg-slate-900 px-4">
      <form onSubmit={submit} className="bg-white rounded-xl shadow-lg p-8 w-full max-w-sm space-y-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">cratebox</h1>
          <p className="text-sm text-gray-500 mt-1">음반 재고·정산 시스템</p>
        </div>
        <Input placeholder="아이디" value={username} onChange={(e) => setUsername(e.target.value)} autoFocus />
        <Input placeholder="비밀번호" type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        {error && <p className="text-sm text-red-600">{error}</p>}
        <Button type="submit" disabled={busy || !username || !password} className="w-full py-2">
          {busy ? '확인 중…' : '로그인'}
        </Button>
      </form>
    </div>
  )
}
