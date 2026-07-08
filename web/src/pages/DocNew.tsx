import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import {
  api, ApiError, DOC_TYPE_LABEL, todayLocal,
  type DocType, type Location, type Party, type Period, type SkuFlat, type StockDoc,
} from '../api'
import { Button, Card, Field, Input, Select } from '../ui'

const PRICED: DocType[] = ['PURCHASE_IN', 'PURCHASE_RETURN', 'SALE_OUT', 'CUSTOMER_RETURN', 'SALES_REPORT',
  'DIRECT_SALE']
const NEGATIVE_OK: DocType[] = ['ADJUST', 'SALES_REPORT']
const RESTATABLE: DocType[] = ['PURCHASE_RETURN', 'CUSTOMER_RETURN', 'SALES_REPORT']
// 상대방이 기획사인 문서
const LABEL_DOCS: DocType[] = ['PURCHASE_IN', 'PURCHASE_RETURN', 'CONSIGN_IN', 'RETURN_TO_OWNER']
// 상대방이 없는 문서
const NO_PARTY: DocType[] = ['TRANSFER', 'ADJUST', 'OPENING', 'DIRECT_SALE']
// 라인에서 재고 구분(자사/위탁)을 고를 수 있는 문서 (수탁입고/반납은 상대방으로 자동 결정)
const OWNERABLE: DocType[] = ['SALE_OUT', 'CUSTOMER_RETURN', 'CONSIGN_PLACE', 'SALES_REPORT',
  'CONSIGN_RECALL', 'TRANSFER', 'ADJUST', 'OPENING', 'DIRECT_SALE']

// 문서 유형별 상대방 조건
function partyFilter(t: DocType): (p: Party) => boolean {
  switch (t) {
    case 'PURCHASE_IN':
    case 'PURCHASE_RETURN':
    case 'CONSIGN_IN':
    case 'RETURN_TO_OWNER':
      return (p) => p.kind === 'LABEL'
    case 'SALE_OUT':
    case 'CUSTOMER_RETURN':
      return (p) => p.kind === 'RETAILER' && p.settlementBasis === 'SELL_IN'
    case 'CONSIGN_PLACE':
    case 'SALES_REPORT':
    case 'CONSIGN_RECALL':
      return (p) => p.kind === 'RETAILER' && p.settlementBasis === 'SELL_THROUGH'
    default:
      return () => false
  }
}

// 문서 유형별 위치 입력: [출발 창고 필요, 도착 창고 필요, 매장 위치 자동(from|to)]
const LOC_RULE: Record<DocType, { fromWh?: boolean; toWh?: boolean; store?: 'from' | 'to' }> = {
  PURCHASE_IN: { toWh: true },
  PURCHASE_RETURN: { fromWh: true },
  SALE_OUT: { fromWh: true },
  CUSTOMER_RETURN: { toWh: true },
  CONSIGN_PLACE: { fromWh: true, store: 'to' },
  SALES_REPORT: { store: 'from' },
  CONSIGN_RECALL: { toWh: true, store: 'from' },
  TRANSFER: { fromWh: true, toWh: true },
  ADJUST: { toWh: true },
  OPENING: { toWh: true },
  CONSIGN_IN: { toWh: true },
  RETURN_TO_OWNER: { fromWh: true },
  DIRECT_SALE: { fromWh: true },
}

// 문서 유형별 한 줄 안내 (처음 쓰는 사람이 유형을 고를 수 있도록)
const DOC_TYPE_DESC: Record<DocType, string> = {
  PURCHASE_IN: '기획사에서 음반을 사입(매입)해 창고에 넣습니다. 기획사에 줄 매입대금이 잡힙니다.',
  PURCHASE_RETURN: '사입했던 음반을 기획사에 반품합니다. 매입대금에서 차감됩니다.',
  SALE_OUT: '출고 기준 거래처에 판매 출고합니다. 출고 즉시 받을 돈(매출)이 잡힙니다.',
  CUSTOMER_RETURN: '거래처에서 반품이 들어옵니다. 받을 돈에서 차감됩니다.',
  CONSIGN_PLACE: '판매분 기준 거래처 매장에 진열용으로 내보냅니다. 이 시점에는 매출이 아닙니다.',
  SALES_REPORT: '매장 진열분 중 실제 판매된 수량을 보고받아 매출로 잡습니다.',
  CONSIGN_RECALL: '매장에 진열했던 미판매분을 창고로 되가져옵니다.',
  TRANSFER: '창고 사이의 재고 이동입니다. 정산에는 영향이 없습니다.',
  ADJUST: '재고 실사 결과와 장부의 차이를 ± 수량으로 맞춥니다.',
  OPENING: '시스템 도입 시점의 기초 재고를 등록합니다.',
  CONSIGN_IN: '기획사 소유 음반을 위탁으로 받아 창고에 보관합니다. 판매되기 전까지는 정산이 생기지 않습니다.',
  RETURN_TO_OWNER: '보관 중인 위탁 재고를 기획사에 돌려보냅니다.',
  DIRECT_SALE: '행사·팝업에서 소비자에게 직접 판매합니다. 재고가 차감되고 위탁 음반은 기획사 정산에 반영되며, 현장에서 받은 돈은 정산 잔액에 잡히지 않습니다. 현장에서는 [현장판매] 메뉴가 더 편합니다.',
}

interface LineDraft { skuId: string; qty: string; unitPrice: string; owner: string; note: string }
const emptyLine = (): LineDraft => ({ skuId: '', qty: '', unitPrice: '', owner: '', note: '' })

export default function DocNew() {
  const nav = useNavigate()
  const [docType, setDocType] = useState<DocType>('PURCHASE_IN')
  const [counterpartyId, setCounterpartyId] = useState('')
  const [fromWh, setFromWh] = useState('')
  const [toWh, setToWh] = useState('')
  const [occurredOn, setOccurredOn] = useState(todayLocal())
  const [restate, setRestate] = useState('')
  const [memo, setMemo] = useState('')
  const [lines, setLines] = useState<LineDraft[]>([emptyLine()])
  const [barcode, setBarcode] = useState('')
  const [error, setError] = useState('')

  const parties = useQuery({ queryKey: ['parties'], queryFn: () => api.get<Party[]>('/api/parties') })
  const locations = useQuery({ queryKey: ['locations'], queryFn: () => api.get<Location[]>('/api/locations') })
  const skus = useQuery({ queryKey: ['skus'], queryFn: () => api.get<SkuFlat[]>('/api/skus') })
  const periods = useQuery({ queryKey: ['periods'], queryFn: () => api.get<Period[]>('/api/settlement/periods') })

  const rule = LOC_RULE[docType]
  const priced = PRICED.includes(docType)
  const consignDoc = docType === 'CONSIGN_IN' || docType === 'RETURN_TO_OWNER'
  const needsParty = !NO_PARTY.includes(docType)
  const eligibleParties = useMemo(
    () => (parties.data ?? []).filter(partyFilter(docType)).filter((p) => p.active),
    [parties.data, docType],
  )
  const warehouses = (locations.data ?? []).filter((l) => l.kind === 'WAREHOUSE' && l.active)
  const cp = eligibleParties.find((p) => String(p.id) === counterpartyId)
  const storeLoc = (locations.data ?? []).find((l) => l.retailerPartyId != null && String(l.retailerPartyId) === counterpartyId)
  const latestClosed = (periods.data ?? []).filter((p) => p.status === 'CLOSED').sort((a, b) => b.yearMonth.localeCompare(a.yearMonth))[0]

  // 거래처 공급률 → 단가 기본값
  function defaultPrice(sku: SkuFlat): string {
    if (!priced) return ''
    if (cp?.kind === 'RETAILER' && cp.defaultSupplyRate) {
      return String(Math.round(sku.listPrice * cp.defaultSupplyRate))
    }
    return ''
  }

  function setLine(i: number, patch: Partial<LineDraft>) {
    setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)))
  }

  // 이 문서 유형에서 선택 가능한 SKU (수탁입고/반납은 상대 기획사의 위탁 계약 앨범만)
  const eligibleSkus = (skus.data ?? []).filter((s) => s.active).filter((s) =>
    consignDoc
      ? String(s.labelPartyId) === counterpartyId && s.agreementKind === 'CONSIGNMENT'
      : true)

  /** 바코드 입력/스캔 → 라인 추가. 이미 담긴 품목이면 수량 +1 */
  function quickAdd() {
    const code = barcode.trim()
    if (!code) return
    const s = eligibleSkus.find((x) => x.barcode === code)
    if (!s) {
      setError(`등록되지 않은 바코드입니다: ${code}`)
      return
    }
    setError('')
    setBarcode('')
    setLines((ls) => {
      const i = ls.findIndex((l) => l.skuId === String(s.id))
      if (i >= 0) return ls.map((l, j) => (j === i ? { ...l, qty: String((Number(l.qty) || 0) + 1) } : l))
      // 위탁 계약 앨범은 위탁 풀이 기본 (현장판매 화면과 동일 규칙) — 자사 판매면 라인에서 바꾼다
      const owner = !consignDoc && OWNERABLE.includes(docType) && s.agreementKind === 'CONSIGNMENT'
        ? String(s.labelPartyId) : ''
      const line: LineDraft = { skuId: String(s.id), qty: '1', unitPrice: defaultPrice(s), owner, note: '' }
      const empty = ls.findIndex((l) => !l.skuId)
      return empty >= 0 ? ls.map((l, j) => (j === empty ? line : l)) : [...ls, line]
    })
  }

  function buildPayload() {
    return {
      docType,
      counterpartyId: needsParty ? Number(counterpartyId) || null : null,
      locationFromId: rule.store === 'from' ? storeLoc?.id ?? null : rule.fromWh ? Number(fromWh) || null : null,
      locationToId: rule.store === 'to' ? storeLoc?.id ?? null : rule.toWh ? Number(toWh) || null : null,
      occurredOn,
      restatePeriodId: restate ? Number(restate) : null,
      memo: memo || null,
      lines: lines
        .filter((l) => l.skuId && l.qty)
        .map((l) => ({
          skuId: Number(l.skuId),
          qty: Number(l.qty),
          unitPrice: priced && l.unitPrice !== '' ? Number(l.unitPrice) : null,
          ownerPartyId: OWNERABLE.includes(docType) && l.owner ? Number(l.owner) : null,
          note: l.note || null,
        })),
    }
  }

  const save = useMutation({
    mutationFn: async (andPost: boolean) => {
      const doc = await api.post<StockDoc>('/api/docs', buildPayload())
      if (andPost) {
        await api.post<StockDoc>(`/api/docs/${doc.id}/post`)
      }
      return doc
    },
    onSuccess: (doc) => nav(`/docs/${doc.id}`),
    onError: (e) => setError(e instanceof ApiError ? e.message : '저장 실패'),
  })

  return (
    <div className="space-y-4 max-w-3xl">
      <h1 className="text-xl font-bold text-stone-900">새 문서</h1>
      <Card>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Field label="문서 유형">
            <Select
              value={docType}
              onChange={(e) => {
                setDocType(e.target.value as DocType)
                setCounterpartyId('')
                setRestate('')
                setLines([emptyLine()])
              }}
            >
              {Object.entries(DOC_TYPE_LABEL).map(([k, v]) => (
                <option key={k} value={k}>{v}</option>
              ))}
            </Select>
          </Field>
          <Field label="일자">
            <Input type="date" value={occurredOn} onChange={(e) => setOccurredOn(e.target.value)} />
          </Field>

          <p className="sm:col-span-2 text-xs text-stone-500 bg-stone-50 border border-stone-100 rounded px-3 py-2">
            {DOC_TYPE_DESC[docType]}
          </p>

          {needsParty && (
            <Field label={LABEL_DOCS.includes(docType) ? '기획사' : '거래처'}>
              <Select value={counterpartyId} onChange={(e) => setCounterpartyId(e.target.value)}>
                <option value="">선택…</option>
                {eligibleParties.map((p) => (
                  <option key={p.id} value={p.id}>{p.name}</option>
                ))}
              </Select>
            </Field>
          )}

          {rule.fromWh && (
            <Field label="출고 창고">
              <Select value={fromWh} onChange={(e) => setFromWh(e.target.value)}>
                <option value="">선택…</option>
                {warehouses.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
              </Select>
            </Field>
          )}
          {rule.toWh && (
            <Field label="입고 창고">
              <Select value={toWh} onChange={(e) => setToWh(e.target.value)}>
                <option value="">선택…</option>
                {warehouses.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
              </Select>
            </Field>
          )}
          {rule.store && (
            <Field label="거래처 매장 위치 (자동)">
              <Input value={storeLoc?.name ?? '거래처를 먼저 선택'} disabled />
            </Field>
          )}

          {RESTATABLE.includes(docType) && latestClosed && (
            <Field label={`마감 처리 (기본: 차기 이월)`}>
              <Select value={restate} onChange={(e) => setRestate(e.target.value)}>
                <option value="">차기 이월</option>
                <option value={latestClosed.id}>{latestClosed.yearMonth} 소급 정정 (정산서 재발행)</option>
              </Select>
            </Field>
          )}

          <Field label="메모">
            <Input value={memo} onChange={(e) => setMemo(e.target.value)} placeholder="선택" />
          </Field>
        </div>
      </Card>

      <Card
        title="품목"
        actions={<Button variant="ghost" onClick={() => setLines((ls) => [...ls, emptyLine()])}>+ 품목 추가</Button>}
      >
        <div className="mb-3 max-w-sm">
          <Input
            placeholder="바코드로 추가 — 스캔/입력 후 Enter" value={barcode} inputMode="numeric"
            onChange={(e) => setBarcode(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); quickAdd() } }}
          />
        </div>
        <div className="space-y-2">
          {lines.map((l, i) => {
            const sku = skus.data?.find((s) => String(s.id) === l.skuId)
            const skuOptions = eligibleSkus
            const ownerable = OWNERABLE.includes(docType) && sku?.agreementKind === 'CONSIGNMENT'
            return (
              <div key={i} className="grid grid-cols-12 gap-2 items-center">
                <div className="col-span-12 sm:col-span-4">
                  <Select
                    value={l.skuId}
                    onChange={(e) => {
                      const s = skus.data?.find((x) => String(x.id) === e.target.value)
                      setLine(i, { skuId: e.target.value, unitPrice: s ? defaultPrice(s) : '', owner: '' })
                    }}
                  >
                    <option value="">
                      {consignDoc && !counterpartyId ? '기획사를 먼저 선택…'
                        : consignDoc && skuOptions.length === 0 ? '위탁 계약 앨범이 없습니다'
                        : '품목 선택…'}
                    </option>
                    {skuOptions.map((s) => (
                      <option key={s.id} value={s.id}>
                        {s.albumTitle} · {s.versionName} · {s.name}
                      </option>
                    ))}
                  </Select>
                </div>
                <div className="col-span-4 sm:col-span-2">
                  <Input
                    type="number" placeholder="수량" value={l.qty}
                    min={NEGATIVE_OK.includes(docType) ? undefined : 1}
                    onChange={(e) => setLine(i, { qty: e.target.value })}
                  />
                </div>
                {priced && (
                  <div className="col-span-5 sm:col-span-2">
                    <Input
                      type="number" placeholder="단가(VAT별도)" value={l.unitPrice}
                      onChange={(e) => setLine(i, { unitPrice: e.target.value })}
                    />
                  </div>
                )}
                <div className={`col-span-6 sm:col-span-3 ${ownerable ? '' : 'text-xs text-stone-400'}`}>
                  {ownerable && sku ? (
                    <Select value={l.owner} onChange={(e) => setLine(i, { owner: e.target.value })}>
                      <option value="">자사 재고</option>
                      <option value={sku.labelPartyId}>위탁: {sku.labelName}</option>
                    </Select>
                  ) : (
                    sku && `정가 ${sku.listPrice.toLocaleString()}`
                  )}
                </div>
                <div className="col-span-3 sm:col-span-1 text-right">
                  <button className="text-stone-400 hover:text-red-500 text-sm" onClick={() => setLines((ls) => ls.filter((_, j) => j !== i))}>
                    삭제
                  </button>
                </div>
              </div>
            )
          })}
        </div>
        {NEGATIVE_OK.includes(docType) && (
          <p className="text-xs text-stone-400 mt-2">
            {docType === 'SALES_REPORT' ? '판매 취소 보고는 음수 수량으로 입력합니다' : '실사 차이는 ± 수량으로 입력합니다'}
          </p>
        )}
      </Card>

      {error && <p className="text-sm text-red-600">{error}</p>}
      <div className="flex gap-2">
        <Button variant="ghost" disabled={save.isPending} onClick={() => save.mutate(false)}>임시저장</Button>
        <Button disabled={save.isPending} onClick={() => save.mutate(true)}>저장 후 확정</Button>
      </div>
    </div>
  )
}
