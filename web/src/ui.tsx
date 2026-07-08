import type { ReactNode, ComponentProps, ButtonHTMLAttributes } from 'react'

export function Card({ title, actions, children }: { title?: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <div className="bg-white border border-stone-200 rounded-md shadow-sm">
      {(title || actions) && (
        <div className="flex items-center justify-between px-4 py-3 border-b border-stone-100">
          <h2 className="font-semibold text-stone-800">{title}</h2>
          <div className="flex gap-2">{actions}</div>
        </div>
      )}
      <div className="p-4">{children}</div>
    </div>
  )
}

export function Table({ head, children }: { head: string[]; children: ReactNode }) {
  return (
    <div className="overflow-x-auto -mx-4 px-4">
      <table className="w-full text-sm whitespace-nowrap">
        <thead>
          <tr className="text-left text-xs text-stone-500 border-b border-stone-200">
            {head.map((h) => (
              <th key={h} className="py-2 pr-4 font-medium">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-stone-100">{children}</tbody>
      </table>
    </div>
  )
}

export function Input(props: ComponentProps<'input'>) {
  return (
    <input
      {...props}
      className={`border border-stone-300 rounded px-2.5 py-1.5 text-sm w-full bg-white focus:outline-none focus:ring-2 focus:ring-emerald-600/25 focus:border-emerald-700 ${props.className ?? ''}`}
    />
  )
}

export function Select(props: ComponentProps<'select'>) {
  return (
    <select
      {...props}
      className={`border border-stone-300 rounded px-2 py-1.5 text-sm w-full bg-white focus:outline-none focus:ring-2 focus:ring-emerald-600/25 focus:border-emerald-700 ${props.className ?? ''}`}
    />
  )
}

export function Button({ variant = 'primary', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'ghost' | 'danger' }) {
  const style =
    variant === 'primary'
      ? 'bg-emerald-800 text-white hover:bg-emerald-900'
      : variant === 'danger'
        ? 'bg-red-50 text-red-700 border border-red-200 hover:bg-red-100'
        : 'bg-white text-stone-700 border border-stone-300 hover:bg-stone-50'
  return (
    <button
      {...props}
      className={`rounded px-3 py-1.5 text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed ${style} ${props.className ?? ''}`}
    />
  )
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="block text-xs text-stone-500 mb-1">{label}</span>
      {children}
    </label>
  )
}

/**
 * 문서 상태 배지. 확정(POSTED)은 도장 모티프 — 이 시스템의 마감·전기가
 * 실제로 '도장(stamping)'이라서, 확정된 것에만 스탬프가 찍힌다.
 */
export function Badge({ status }: { status: string }) {
  if (status === 'POSTED') return <Stamp>확정</Stamp>
  const map: Record<string, { cls: string; label: string }> = {
    DRAFT: { cls: 'border-dashed border-stone-400 text-stone-500 bg-white', label: '작성중' },
    REVERSED: { cls: 'border-stone-200 bg-stone-100 text-stone-400 line-through', label: '취소됨' },
  }
  const m = map[status] ?? { cls: 'bg-stone-50 border-stone-200 text-stone-500', label: status }
  return (
    <span className={`inline-block text-xs px-1.5 py-0.5 rounded border ${m.cls}`}>
      {m.label}
    </span>
  )
}

/** 도장 스타일 표시 (확정·정산 확정 등) — 살짝 기울어진 스탬프 */
export function Stamp({ children }: { children: ReactNode }) {
  return (
    <span className="inline-block -rotate-2 text-xs font-semibold tracking-widest px-1.5 py-0.5 rounded-sm border-2 border-emerald-700/70 text-emerald-800 bg-emerald-50/40">
      {children}
    </span>
  )
}

/** 정산서 머리글의 발행사 표기 (운영·포털 공용) */
export function IssuerLine({ issuer }: {
  issuer?: { name?: string; bizRegNo?: string | null; phone?: string | null; address?: string | null } | null
}) {
  if (!issuer?.name) return null
  return (
    <p className="text-xs text-stone-400 mt-1">
      발행사: {issuer.name}
      {issuer.bizRegNo && ` · 사업자 ${issuer.bizRegNo}`}
      {issuer.phone && ` · ${issuer.phone}`}
      {issuer.address && ` · ${issuer.address}`}
    </p>
  )
}

export function Money({ value }: { value: number }) {
  return (
    <span className={`tabular-nums ${value < 0 ? 'text-red-600' : ''}`}>
      {value.toLocaleString('ko-KR')}
    </span>
  )
}
