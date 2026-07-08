import { useState } from 'react'
import { Routes, Route, NavLink, Navigate, useLocation } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, type Me } from './api'
import Login from './pages/Login'
import Stock from './pages/Stock'
import Docs from './pages/Docs'
import DocNew from './pages/DocNew'
import DocDetail from './pages/DocDetail'
import Parties from './pages/Parties'
import Catalog from './pages/Catalog'
import Payments from './pages/Payments'
import Advances from './pages/Advances'
import Settlement from './pages/Settlement'
import StatementPage from './pages/StatementDetail'
import Reports from './pages/Reports'
import PortalApp from './pages/Portal'

const MENU = [
  { to: '/stock', label: '재고 현황' },
  { to: '/docs', label: '입출고 문서' },
  { to: '/settlement', label: '정산·마감' },
  { to: '/payments', label: '입금·지급' },
  { to: '/advances', label: 'MG 선급금' },
  { to: '/reports', label: '리포트' },
  { to: '/catalog', label: '상품 관리' },
  { to: '/parties', label: '거래처·기획사' },
]

export default function App() {
  const location = useLocation()
  if (location.pathname === '/login') {
    return (
      <Routes>
        <Route path="/login" element={<Login />} />
      </Routes>
    )
  }
  return <Shell />
}

function Shell() {
  const [open, setOpen] = useState(false)
  const me = useQuery({ queryKey: ['me'], queryFn: () => api.get<Me>('/api/auth/me') })

  if (me.isLoading) {
    return <div className="min-h-screen grid place-items-center text-gray-400">불러오는 중…</div>
  }
  if (me.isError) {
    return <Navigate to="/login" replace />
  }
  if (me.data?.role === 'LABEL') {
    return <PortalApp me={me.data} />
  }

  return (
    <div className="min-h-screen bg-gray-50 md:flex">
      {/* 모바일 상단바 */}
      <header className="md:hidden flex items-center justify-between bg-slate-900 text-white px-4 py-3 sticky top-0 z-20">
        <span className="font-bold">cratebox</span>
        <button onClick={() => setOpen(!open)} className="text-xl leading-none" aria-label="메뉴">☰</button>
      </header>

      {/* 사이드바 */}
      <aside className={`${open ? 'block' : 'hidden'} md:block bg-slate-900 text-slate-200 md:w-52 shrink-0 z-10`}>
        <div className="hidden md:block px-4 py-4 font-bold text-white text-lg">cratebox</div>
        <nav className="px-2 pb-2 md:pb-4 space-y-0.5">
          {MENU.map((m) => (
            <NavLink
              key={m.to}
              to={m.to}
              onClick={() => setOpen(false)}
              className={({ isActive }) =>
                `block rounded px-3 py-2 text-sm ${isActive ? 'bg-indigo-600 text-white' : 'hover:bg-slate-800'}`
              }
            >
              {m.label}
            </NavLink>
          ))}
        </nav>
        <div className="px-4 py-3 border-t border-slate-800 text-xs text-slate-400 flex items-center justify-between">
          <span>{me.data?.displayName}</span>
          <button
            className="underline hover:text-white"
            onClick={async () => {
              await api.post('/api/auth/logout')
              window.location.href = '/login'
            }}
          >
            로그아웃
          </button>
        </div>
      </aside>

      <main className="flex-1 p-4 md:p-6 max-w-6xl">
        <Routes>
          <Route path="/" element={<Navigate to="/stock" replace />} />
          <Route path="/stock" element={<Stock />} />
          <Route path="/docs" element={<Docs />} />
          <Route path="/docs/new" element={<DocNew />} />
          <Route path="/docs/:id" element={<DocDetail />} />
          <Route path="/settlement" element={<Settlement />} />
          <Route path="/settlement/statements/:id" element={<StatementPage />} />
          <Route path="/payments" element={<Payments />} />
          <Route path="/advances" element={<Advances />} />
          <Route path="/reports" element={<Reports />} />
          <Route path="/catalog" element={<Catalog />} />
          <Route path="/parties" element={<Parties />} />
        </Routes>
      </main>
    </div>
  )
}
