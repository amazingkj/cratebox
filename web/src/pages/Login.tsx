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
    <div className="min-h-screen grid place-items-center bg-stone-950 grooves px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6 text-center">
          <h1 className="text-3xl font-bold tracking-tight text-white">
            crate<span className="text-emerald-400">box</span>
          </h1>
          <p className="text-sm text-stone-400 mt-2">음반 재고·정산 장부</p>
        </div>
        <form onSubmit={submit} className="bg-white rounded-md border border-stone-200 shadow-lg p-8 space-y-4">
          <Input placeholder="아이디" value={username} onChange={(e) => setUsername(e.target.value)} autoFocus />
          <Input placeholder="비밀번호" type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
          {error && <p className="text-sm text-red-600">{error}</p>}
          <Button type="submit" disabled={busy || !username || !password} className="w-full py-2">
            {busy ? '확인 중…' : '로그인'}
          </Button>
        </form>
      </div>
    </div>
  )
}
