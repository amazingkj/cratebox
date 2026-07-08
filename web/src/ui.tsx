import type { ReactNode, InputHTMLAttributes, SelectHTMLAttributes, ButtonHTMLAttributes } from 'react'

export function Card({ title, actions, children }: { title?: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <div className="bg-white border border-gray-200 rounded-lg shadow-sm">
      {(title || actions) && (
        <div className="flex items-center justify-between px-4 py-3 border-b border-gray-100">
          <h2 className="font-semibold text-gray-800">{title}</h2>
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
          <tr className="text-left text-gray-500 border-b border-gray-200">
            {head.map((h) => (
              <th key={h} className="py-2 pr-4 font-medium">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">{children}</tbody>
      </table>
    </div>
  )
}

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      {...props}
      className={`border border-gray-300 rounded px-2.5 py-1.5 text-sm w-full focus:outline-none focus:ring-2 focus:ring-indigo-300 ${props.className ?? ''}`}
    />
  )
}

export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select
      {...props}
      className={`border border-gray-300 rounded px-2 py-1.5 text-sm w-full bg-white focus:outline-none focus:ring-2 focus:ring-indigo-300 ${props.className ?? ''}`}
    />
  )
}

export function Button({ variant = 'primary', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'ghost' | 'danger' }) {
  const style =
    variant === 'primary'
      ? 'bg-indigo-600 text-white hover:bg-indigo-700'
      : variant === 'danger'
        ? 'bg-red-50 text-red-700 border border-red-200 hover:bg-red-100'
        : 'bg-white text-gray-700 border border-gray-300 hover:bg-gray-50'
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
      <span className="block text-xs text-gray-500 mb-1">{label}</span>
      {children}
    </label>
  )
}

export function Badge({ status }: { status: string }) {
  const map: Record<string, string> = {
    DRAFT: 'bg-amber-50 text-amber-700 border-amber-200',
    POSTED: 'bg-emerald-50 text-emerald-700 border-emerald-200',
    REVERSED: 'bg-gray-100 text-gray-500 border-gray-200',
  }
  const label: Record<string, string> = { DRAFT: '작성중', POSTED: '확정', REVERSED: '역분개됨' }
  return (
    <span className={`inline-block text-xs px-1.5 py-0.5 rounded border ${map[status] ?? 'bg-gray-50 border-gray-200'}`}>
      {label[status] ?? status}
    </span>
  )
}

export function Money({ value }: { value: number }) {
  return (
    <span className={`tabular-nums ${value < 0 ? 'text-red-600' : ''}`}>
      {value.toLocaleString('ko-KR')}
    </span>
  )
}
