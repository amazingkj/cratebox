import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BarcodeDetector, prepareZXingModule } from 'barcode-detector/ponyfill'
import wasmUrl from 'zxing-wasm/reader/zxing_reader.wasm?url'
import { api, ApiError, fmt, todayLocal, type Location, type SkuFlat, type StockDoc } from '../api'
import { Button, Card, Input, Select } from '../ui'

// 바코드 인식은 zxing-wasm 기반 폴리필 — iOS 사파리 포함 전 브라우저 동일 동작.
// wasm은 CDN이 아니라 번들 자산에서 로드한다 (오프라인/사내망 동작 보장)
prepareZXingModule({
  overrides: {
    locateFile: (path: string, prefix: string) => (path.endsWith('.wasm') ? wasmUrl : prefix + path),
  },
})

interface SaleLine { skuId: number; qty: number; unitPrice: number; owner: number | null }

/** 라인 합계(VAT 포함) — 원장과 같은 라인별 VAT 절사 규칙 */
const lineTotal = (l: SaleLine) => {
  const supply = l.unitPrice * l.qty
  return supply + Math.trunc(supply / 10)
}

/** 현장판매: 행사·팝업에서 바코드 찍고 판매 확정. 재고 차감 + 위탁분은 기획사 정산 자동 */
export default function FieldSale() {
  const qc = useQueryClient()
  const skus = useQuery({ queryKey: ['skus'], queryFn: () => api.get<SkuFlat[]>('/api/skus') })
  const locations = useQuery({ queryKey: ['locations'], queryFn: () => api.get<Location[]>('/api/locations') })
  const warehouses = (locations.data ?? []).filter((l) => l.kind === 'WAREHOUSE' && l.active)

  const [wh, setWh] = useState('')
  const [lines, setLines] = useState<SaleLine[]>([])
  const [barcode, setBarcode] = useState('')
  const [lastScan, setLastScan] = useState('')
  const [error, setError] = useState('')
  const [done, setDone] = useState<StockDoc | null>(null)
  const [scanning, setScanning] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (!wh && warehouses.length > 0) setWh(String(warehouses[0].id))
  }, [wh, warehouses])

  const skuOf = (id: number) => skus.data?.find((s) => s.id === id)

  function addByBarcode(code: string): boolean {
    const s = skus.data?.find((x) => x.active && x.barcode === code.trim())
    if (!s) {
      setError(`등록되지 않은 바코드입니다: ${code}`)
      navigator.vibrate?.(200)
      return false
    }
    addSku(s)
    return true
  }

  /** 같은 품목을 다시 찍으면 수량 +1. 위탁 계약 앨범은 위탁 풀에서 판매가 기본 */
  function addSku(s: SkuFlat) {
    setLines((ls) => {
      const i = ls.findIndex((l) => l.skuId === s.id)
      if (i >= 0) return ls.map((l, j) => (j === i ? { ...l, qty: l.qty + 1 } : l))
      return [...ls, {
        skuId: s.id, qty: 1,
        // 정가(VAT 포함) → 공급단가 역산. ceil이어야 공급가+절사VAT 합이 정가와 일치한다 (예: 15,000 → 13,637+1,363)
        unitPrice: Math.ceil(s.listPrice * 10 / 11),
        owner: s.agreementKind === 'CONSIGNMENT' ? s.labelPartyId : null,
      }]
    })
    setLastScan(`${s.albumTitle} · ${s.name} +1`)
    setError('')
    setDone(null)
    navigator.vibrate?.(50)
  }

  function setLine(i: number, patch: Partial<SaleLine>) {
    setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)))
  }

  const totalQty = lines.reduce((s, l) => s + l.qty, 0)
  const total = lines.reduce((s, l) => s + lineTotal(l), 0)

  const confirmSale = useMutation({
    mutationFn: async () => {
      const doc = await api.post<StockDoc>('/api/docs', {
        docType: 'DIRECT_SALE',
        locationFromId: Number(wh),
        occurredOn: todayLocal(),
        lines: lines.map((l) => ({
          skuId: l.skuId, qty: l.qty, unitPrice: l.unitPrice, ownerPartyId: l.owner,
        })),
      })
      try {
        return await api.post<StockDoc>(`/api/docs/${doc.id}/post`)
      } catch (e) {
        // 확정 실패(재고 부족 등) 시 고아 DRAFT가 남지 않도록 정리
        await api.del(`/api/docs/${doc.id}`).catch(() => {})
        throw e
      }
    },
    onSuccess: (doc) => {
      qc.invalidateQueries({ queryKey: ['stock'] })
      qc.invalidateQueries({ queryKey: ['docs'] })
      setLines([])
      setLastScan('')
      setError('')
      setDone(doc)
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : '확정 실패'),
  })

  return (
    <div className="space-y-4 max-w-xl">
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <h1 className="text-xl font-bold text-stone-900">현장판매</h1>
        <div className="w-44">
          <Select value={wh} onChange={(e) => setWh(e.target.value)}>
            {warehouses.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
          </Select>
        </div>
      </div>
      <p className="text-xs text-stone-500">
        바코드를 찍으면 판매 목록에 담기고, 같은 품목은 수량이 올라갑니다. 확정하면 선택한 창고에서
        재고가 차감되고 위탁 음반은 기획사 정산에 자동 반영됩니다. 현장에서 받은 돈은 정산 잔액에 잡히지 않습니다.
      </p>

      <Card>
        <div className="flex gap-2">
          <Input
            ref={inputRef} value={barcode} placeholder="바코드 입력 후 Enter"
            inputMode="numeric" autoFocus
            onChange={(e) => setBarcode(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && barcode.trim()) {
                e.preventDefault()
                if (addByBarcode(barcode)) setBarcode('')
                inputRef.current?.focus()
              }
            }}
          />
          <Button variant="ghost" className="shrink-0" onClick={() => setScanning(true)}>
            📷 카메라
          </Button>
        </div>
        {lastScan && <p className="text-sm text-emerald-700 mt-2">{lastScan}</p>}
        {error && <p className="text-sm text-red-600 mt-2">{error}</p>}
        {done && (
          <p className="text-sm text-emerald-700 mt-2">
            판매 확정 완료 — <Link className="underline font-mono" to={`/docs/${done.id}`}>{done.docNo}</Link>
          </p>
        )}
      </Card>

      <Card title={`판매 목록 (${fmt(totalQty)}개)`}>
        {lines.length === 0 && <p className="py-6 text-center text-stone-400 text-sm">바코드를 찍으면 여기에 담깁니다</p>}
        <div className="divide-y divide-stone-100">
          {lines.map((l, i) => {
            const s = skuOf(l.skuId)
            return (
              <div key={l.skuId} className="py-3 space-y-2">
                <div className="flex items-center justify-between gap-2">
                  <p className="text-sm font-medium">{s ? `${s.albumTitle} · ${s.versionName} · ${s.name}` : `SKU #${l.skuId}`}</p>
                  <button className="text-stone-400 hover:text-red-500 text-sm shrink-0"
                          onClick={() => setLines((ls) => ls.filter((_, j) => j !== i))}>
                    삭제
                  </button>
                </div>
                <div className="flex items-center gap-2 flex-wrap">
                  <div className="flex items-center gap-1">
                    <Button variant="ghost" className="w-10 h-10 text-lg p-0"
                            onClick={() => setLine(i, { qty: Math.max(1, l.qty - 1) })}>−</Button>
                    <span className="w-10 text-center tabular-nums font-medium">{l.qty}</span>
                    <Button variant="ghost" className="w-10 h-10 text-lg p-0"
                            onClick={() => setLine(i, { qty: l.qty + 1 })}>+</Button>
                  </div>
                  <div className="w-28">
                    <Input type="number" value={l.unitPrice}
                           onChange={(e) => setLine(i, { unitPrice: Number(e.target.value) || 0 })} />
                  </div>
                  <span className="text-xs text-stone-400">공급단가(VAT별도)</span>
                  {s?.agreementKind === 'CONSIGNMENT' && (
                    <div className="w-36">
                      <Select value={l.owner ?? ''} onChange={(e) => setLine(i, { owner: e.target.value ? Number(e.target.value) : null })}>
                        <option value={s.labelPartyId}>위탁: {s.labelName}</option>
                        <option value="">자사 재고</option>
                      </Select>
                    </div>
                  )}
                  <span className="ml-auto text-sm tabular-nums font-medium">
                    {fmt(lineTotal(l))}원
                  </span>
                </div>
              </div>
            )
          })}
        </div>
        {lines.length > 0 && (
          <div className="border-t border-stone-200 mt-2 pt-3 flex items-center justify-between">
            <span className="text-sm text-stone-500">합계 (VAT 포함)</span>
            <span className="text-lg font-bold tabular-nums">{fmt(total)}원</span>
          </div>
        )}
      </Card>

      <Button className="w-full py-3 text-base"
              disabled={lines.length === 0 || !wh || confirmSale.isPending}
              onClick={() => confirmSale.mutate()}>
        판매 확정 ({fmt(totalQty)}개 · {fmt(total)}원)
      </Button>

      {scanning && (
        <Scanner
          onScan={(code) => { addByBarcode(code) }}
          onClose={() => setScanning(false)}
        />
      )}
    </div>
  )
}

/** 카메라 연속 스캔 오버레이. 같은 바코드는 2초 쿨다운 */
function Scanner({ onScan, onClose }: { onScan: (code: string) => void; onClose: () => void }) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const [err, setErr] = useState('')
  // 스트림은 마운트 시 1회만 열되, 핸들러는 항상 최신 렌더의 것을 쓴다 (스캔 시점의 stale 데이터 방지)
  const onScanRef = useRef(onScan)
  onScanRef.current = onScan

  useEffect(() => {
    if (!window.isSecureContext) {
      setErr('카메라는 보안 연결에서만 열립니다 — https 주소(예: https://<서버주소>:8443)로 접속하세요')
      return
    }
    if (!navigator.mediaDevices?.getUserMedia) {
      setErr('이 브라우저는 카메라를 지원하지 않습니다')
      return
    }
    const detector = new BarcodeDetector({ formats: ['ean_13', 'ean_8', 'upc_a', 'code_128'] })
    let stream: MediaStream | undefined
    let timer: number | undefined
    let cancelled = false
    let last = ''
    let lastAt = 0
    navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
      .then((s) => {
        if (cancelled) {
          // 권한 응답 전에 오버레이를 닫은 경우 — 카메라를 바로 반납
          s.getTracks().forEach((t) => t.stop())
          return
        }
        stream = s
        const v = videoRef.current
        if (v) {
          v.srcObject = s
          v.play().catch(() => {})
        }
        timer = window.setInterval(async () => {
          const video = videoRef.current
          if (!video || video.readyState < 2) return
          try {
            for (const b of await detector.detect(video)) {
              const now = Date.now()
              if (b.rawValue === last && now - lastAt < 2000) continue
              last = b.rawValue
              lastAt = now
              onScanRef.current(b.rawValue)
            }
          } catch { /* 프레임 인식 실패는 다음 주기에 재시도 */ }
        }, 300)
      })
      .catch(() => setErr('카메라를 열 수 없습니다 — 브라우저 권한을 확인하세요'))
    return () => {
      cancelled = true
      if (timer) clearInterval(timer)
      stream?.getTracks().forEach((t) => t.stop())
    }
  }, [])

  return (
    <div className="fixed inset-0 z-50 bg-black/90 flex flex-col items-center justify-center p-4">
      <video ref={videoRef} playsInline muted className="w-full max-w-md rounded-md" />
      {err && <p className="text-sm text-red-400 mt-3">{err}</p>}
      <p className="text-xs text-stone-400 mt-3">바코드를 화면 중앙에 맞추세요 — 인식되면 자동으로 담깁니다</p>
      <Button variant="ghost" className="mt-4" onClick={onClose}>닫기</Button>
    </div>
  )
}
