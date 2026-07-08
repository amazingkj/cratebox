// 엑셀에서 바로 열리는 CSV(UTF-8 BOM) 다운로드
type Cell = string | number | null | undefined

export function downloadCsv(filename: string, header: string[], rows: Cell[][]) {
  const esc = (v: Cell) => {
    const s = v == null ? '' : String(v)
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
  }
  const body = [header, ...rows].map((r) => r.map(esc).join(',')).join('\r\n')
  const blob = new Blob([String.fromCharCode(0xfeff) + body], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = filename
  a.click()
  URL.revokeObjectURL(a.href)
}
