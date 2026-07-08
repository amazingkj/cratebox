package io.cratebox.inventory;

import io.cratebox.common.DomainException;
import io.cratebox.common.Vat;
import io.cratebox.inventory.StockDoc.StockDocLine;
import io.cratebox.party.Party;
import io.cratebox.party.PartyRepository;
import io.cratebox.settlement.ClosingService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문서 CRUD + 전기(posting) 엔진.
 * 전기 규칙: docs/DATA-MODEL.md §2 매트릭스. 원장은 append-only, 수정은 역분개뿐.
 */
@Service
public class StockDocService {

    private record BalanceKey(Long skuId, Long locationId, Long ownerPartyId) {}

    /** 라인 SKU의 앨범·기획사·거래 계약 정보 (검증과 위탁 전기에 사용) */
    private record SkuAlbum(Long albumId, Long labelPartyId, String agreementKind, BigDecimal commissionRate) {}

    private final JdbcClient jdbc;
    private final StockDocDao dao;
    private final DocNoService docNo;
    private final PartyRepository parties;
    private final LocationRepository locations;
    private final ClosingService closing;

    public StockDocService(JdbcClient jdbc, StockDocDao dao, DocNoService docNo,
                           PartyRepository parties, LocationRepository locations, ClosingService closing) {
        this.jdbc = jdbc;
        this.dao = dao;
        this.docNo = docNo;
        this.parties = parties;
        this.locations = locations;
        this.closing = closing;
    }

    // ── 문서 CRUD (DRAFT) ─────────────────────────────

    @Transactional
    public StockDoc createDraft(Long orgId, Long userId, StockDoc req) {
        StockDoc doc = new StockDoc(null, orgId, docNo.next(orgId, req.docType(), req.occurredOn()),
                req.docType(), "DRAFT", req.counterpartyId(), req.locationFromId(), req.locationToId(),
                req.occurredOn(), req.restatePeriodId(), req.memo(), userId, null, null, null, null,
                normalizeLines(req));
        validate(doc);
        Long id = dao.insert(doc);
        return dao.findById(orgId, id);
    }

    @Transactional
    public StockDoc updateDraft(Long orgId, Long docId, StockDoc req) {
        StockDoc existing = dao.findById(orgId, docId);
        if (!"DRAFT".equals(existing.status())) {
            throw new DomainException("DRAFT 문서만 수정할 수 있습니다");
        }
        StockDoc doc = new StockDoc(docId, orgId, existing.docNo(), existing.docType(), "DRAFT",
                req.counterpartyId(), req.locationFromId(), req.locationToId(), req.occurredOn(),
                req.restatePeriodId(), req.memo(), existing.createdBy(), existing.createdAt(),
                null, null, null, normalizeLines(req));
        validate(doc);
        dao.updateDraft(doc);
        return dao.findById(orgId, docId);
    }

    /** 라인 번호 재부여, 정산 없는 타입은 단가 제거. 수탁입고/반납의 소유는 상대 기획사로 결정되므로 라인 값 무시 */
    private List<StockDocLine> normalizeLines(StockDoc req) {
        if (req.lines() == null || req.lines().isEmpty()) {
            throw new DomainException("문서에는 라인이 1개 이상 필요합니다");
        }
        boolean ownerImplied = req.docType() == DocType.CONSIGN_IN || req.docType() == DocType.RETURN_TO_OWNER;
        List<StockDocLine> out = new ArrayList<>();
        int no = 1;
        for (StockDocLine l : req.lines()) {
            out.add(new StockDocLine(null, no++, l.skuId(), l.qty(),
                    req.docType().priced() ? l.unitPrice() : null,
                    ownerImplied ? null : l.ownerPartyId(), l.note()));
        }
        return out;
    }

    // ── 검증 (작성 시 + 확정 시 동일 규칙) ─────────────

    private Map<Long, SkuAlbum> validate(StockDoc doc) {
        DocType t = doc.docType();
        Party cp = null;
        if (requiresCounterparty(t)) {
            if (doc.counterpartyId() == null) {
                throw new DomainException("상대방(거래처/기획사)이 필요합니다");
            }
            cp = parties.findByIdAndOrgId(doc.counterpartyId(), doc.orgId())
                    .orElseThrow(() -> new DomainException("상대방이 없습니다: " + doc.counterpartyId()));
        }
        switch (t) {
            case PURCHASE_IN, PURCHASE_RETURN, CONSIGN_IN, RETURN_TO_OWNER -> requireLabel(cp);
            case SALE_OUT, CUSTOMER_RETURN -> requireRetailer(cp, "SELL_IN");
            case CONSIGN_PLACE, SALES_REPORT, CONSIGN_RECALL -> requireRetailer(cp, "SELL_THROUGH");
            default -> { }
        }
        switch (t) {
            case PURCHASE_IN, CUSTOMER_RETURN, ADJUST, OPENING, CONSIGN_IN ->
                    requireWarehouse(doc, doc.locationToId(), "입고 창고");
            case PURCHASE_RETURN, SALE_OUT, RETURN_TO_OWNER, DIRECT_SALE ->
                    requireWarehouse(doc, doc.locationFromId(), "출고 창고");
            case CONSIGN_PLACE -> {
                requireWarehouse(doc, doc.locationFromId(), "출고 창고");
                requireRetailerLocation(doc, doc.locationToId(), cp);
            }
            case SALES_REPORT -> requireRetailerLocation(doc, doc.locationFromId(), cp);
            case CONSIGN_RECALL -> {
                requireRetailerLocation(doc, doc.locationFromId(), cp);
                requireWarehouse(doc, doc.locationToId(), "입고 창고");
            }
            case TRANSFER -> {
                requireWarehouse(doc, doc.locationFromId(), "출발 창고");
                requireWarehouse(doc, doc.locationToId(), "도착 창고");
                if (doc.locationFromId().equals(doc.locationToId())) {
                    throw new DomainException("출발 창고와 도착 창고가 같습니다");
                }
            }
        }
        Map<Long, SkuAlbum> skuAlbums = loadSkuAlbums(doc);
        for (StockDocLine l : doc.lines()) {
            if (l.qty() == 0) {
                throw new DomainException("수량은 0일 수 없습니다");
            }
            if (l.qty() < 0 && !t.allowsNegativeQty()) {
                throw new DomainException(t + " 문서는 음수 수량을 허용하지 않습니다");
            }
            if (t.priced() && (l.unitPrice() == null || l.unitPrice() < 0)) {
                throw new DomainException("단가가 필요합니다 (SKU " + l.skuId() + ")");
            }
            validateLineOwner(doc, t, l, skuAlbums.get(l.skuId()));
        }
        if (doc.restatePeriodId() != null) {
            validateRestate(doc, t);
        }
        return skuAlbums;
    }

    /** 위탁 라인 검증: 소유자 = 앨범의 기획사, 위탁 계약 필수. 사입 문서는 자사 재고만 */
    private void validateLineOwner(StockDoc doc, DocType t, StockDocLine l, SkuAlbum sa) {
        if (t == DocType.CONSIGN_IN || t == DocType.RETURN_TO_OWNER) {
            if (!sa.labelPartyId().equals(doc.counterpartyId())) {
                throw new DomainException("상대 기획사의 앨범이 아닙니다 (SKU " + l.skuId() + ")");
            }
            requireConsignmentAgreement(l, sa);
            return;
        }
        if (l.ownerPartyId() == null) {
            return;
        }
        if (t == DocType.PURCHASE_IN || t == DocType.PURCHASE_RETURN) {
            throw new DomainException("사입입고/매입반품 라인에는 위탁 재고를 지정할 수 없습니다");
        }
        if (!l.ownerPartyId().equals(sa.labelPartyId())) {
            throw new DomainException("위탁 소유자는 해당 앨범의 기획사여야 합니다 (SKU " + l.skuId() + ")");
        }
        requireConsignmentAgreement(l, sa);
    }

    private void requireConsignmentAgreement(StockDocLine l, SkuAlbum sa) {
        if (!"CONSIGNMENT".equals(sa.agreementKind())) {
            throw new DomainException("위탁 계약이 등록되지 않은 앨범입니다 (SKU " + l.skuId()
                    + ") — 상품 화면에서 거래 계약을 먼저 등록하세요");
        }
    }

    private Map<Long, SkuAlbum> loadSkuAlbums(StockDoc doc) {
        List<Long> skuIds = doc.lines().stream().map(StockDocLine::skuId).distinct().toList();
        Map<Long, SkuAlbum> map = new HashMap<>();
        jdbc.sql("""
                select k.id, a.id as album_id, a.label_party_id, ta.kind, ta.commission_rate
                from sku k
                join album_version v on v.id = k.album_version_id
                join album a on a.id = v.album_id
                left join trade_agreement ta on ta.album_id = a.id
                where k.org_id = :org and k.id in (:ids)
                """)
                .param("org", doc.orgId()).param("ids", skuIds)
                .query((rs, i) -> Map.entry(rs.getLong("id"), new SkuAlbum(rs.getLong("album_id"),
                        rs.getLong("label_party_id"), rs.getString("kind"), rs.getBigDecimal("commission_rate"))))
                .list()
                .forEach(e -> map.put(e.getKey(), e.getValue()));
        if (map.size() != skuIds.size()) {
            throw new DomainException("존재하지 않는 SKU가 포함되어 있습니다");
        }
        return map;
    }

    private void validateRestate(StockDoc doc, DocType t) {
        // 현장판매는 자사 라인이면 정산 엔트리가 없어 소급이 무의미하고, 정정은 역분개로 처리한다
        if (!t.priced() || t == DocType.DIRECT_SALE) {
            throw new DomainException("정산이 발생하지 않는 문서는 소급 정정할 수 없습니다");
        }
        var period = jdbc.sql("""
                select id, year_month from settlement_period
                where id = :id and org_id = :org and status = 'CLOSED'
                """)
                .param("id", doc.restatePeriodId()).param("org", doc.orgId())
                .query((rs, i) -> Map.entry(rs.getLong("id"), rs.getString("year_month")))
                .optional()
                .orElseThrow(() -> new DomainException("소급 대상은 마감된 기간이어야 합니다"));
        String latest = jdbc.sql(
                "select max(year_month) from settlement_period where org_id = :org and status = 'CLOSED'")
                .param("org", doc.orgId()).query(String.class).single();
        if (!period.getValue().equals(latest)) {
            throw new DomainException("소급 정정은 직전 마감 기간(" + latest + ")까지만 허용됩니다");
        }
    }

    private boolean requiresCounterparty(DocType t) {
        return switch (t) {
            case TRANSFER, ADJUST, OPENING, DIRECT_SALE -> false;
            default -> true;
        };
    }

    private void requireLabel(Party p) {
        if (!p.isLabel()) {
            throw new DomainException("기획사(LABEL)만 지정할 수 있습니다: " + p.name());
        }
    }

    private void requireRetailer(Party p, String basis) {
        if (!p.isRetailer() || !basis.equals(p.settlementBasis())) {
            throw new DomainException("정산 기준이 " + basis + "인 거래처만 지정할 수 있습니다: " + p.name());
        }
    }

    private void requireWarehouse(StockDoc doc, Long locationId, String label) {
        if (locationId == null) {
            throw new DomainException(label + "가 필요합니다");
        }
        Location loc = locations.findByIdAndOrgId(locationId, doc.orgId())
                .orElseThrow(() -> new DomainException(label + "가 없습니다: " + locationId));
        if (!loc.isWarehouse()) {
            throw new DomainException(label + "는 자사 창고여야 합니다: " + loc.name());
        }
    }

    private void requireRetailerLocation(StockDoc doc, Long locationId, Party cp) {
        if (locationId == null) {
            throw new DomainException("거래처 매장 위치가 필요합니다");
        }
        Location loc = locations.findByIdAndOrgId(locationId, doc.orgId())
                .orElseThrow(() -> new DomainException("위치가 없습니다: " + locationId));
        if (loc.retailerPartyId() == null || !loc.retailerPartyId().equals(cp.id())) {
            throw new DomainException("해당 거래처의 매장 위치가 아닙니다: " + loc.name());
        }
    }

    // ── 전기 (POST) ───────────────────────────────────

    @Transactional
    public StockDoc post(Long orgId, Long userId, Long docId) {
        StockDoc doc = dao.findById(orgId, docId);
        if (!"DRAFT".equals(doc.status())) {
            throw new DomainException("DRAFT 문서만 확정할 수 있습니다 (현재: " + doc.status() + ")");
        }
        Map<Long, SkuAlbum> skuAlbums = validate(doc);
        Map<BalanceKey, Long> deltas = new HashMap<>();
        for (StockDocLine line : doc.lines()) {
            postLine(doc, line, skuAlbums, deltas);
        }
        applyBalances(orgId, deltas);
        dao.markPosted(docId);
        if (doc.restatePeriodId() != null) {
            // 위탁 라인은 기획사 정산서도 함께 재발행 (상대방 없는 문서는 라인 소유자만)
            Set<Long> cps = new LinkedHashSet<>();
            if (doc.counterpartyId() != null) {
                cps.add(doc.counterpartyId());
            }
            doc.lines().stream().map(StockDocLine::ownerPartyId).filter(Objects::nonNull).forEach(cps::add);
            for (Long cpId : cps) {
                closing.regenerateStatement(orgId, userId, doc.restatePeriodId(), cpId);
            }
        }
        return dao.findById(orgId, docId);
    }

    /** 전기 매트릭스: 한 라인 → 재고 엔트리(1~2행) + 정산 엔트리(0~2행). 위탁 라인은 기획사 정산이 추가된다 */
    private void postLine(StockDoc doc, StockDocLine line, Map<Long, SkuAlbum> skuAlbums,
                          Map<BalanceKey, Long> deltas) {
        int qty = line.qty();
        Long owner = line.ownerPartyId();
        switch (doc.docType()) {
            case PURCHASE_IN -> {
                inv(doc, line, "PURCHASE_IN", doc.locationToId(), qty, null, deltas);
                settle(doc, line, "PURCHASE", -1);
            }
            case PURCHASE_RETURN -> {
                inv(doc, line, "PURCHASE_RETURN", doc.locationFromId(), -qty, null, deltas);
                settle(doc, line, "PURCHASE_RETURN", +1);
            }
            case SALE_OUT -> {
                inv(doc, line, "SALE_OUT", doc.locationFromId(), -qty, owner, deltas);
                settle(doc, line, "SALE", +1);
                settleOwner(doc, line, "CONSIGN_SALE", -1, skuAlbums);
            }
            case CUSTOMER_RETURN -> {
                inv(doc, line, "CUSTOMER_RETURN", doc.locationToId(), qty, owner, deltas);
                settle(doc, line, "SALE_RETURN", -1);
                settleOwner(doc, line, "CONSIGN_RETURN", +1, skuAlbums);
            }
            case CONSIGN_PLACE -> {
                inv(doc, line, "CONSIGN_PLACE_OUT", doc.locationFromId(), -qty, owner, deltas);
                inv(doc, line, "CONSIGN_PLACE_IN", doc.locationToId(), qty, owner, deltas);
            }
            case SALES_REPORT -> {
                inv(doc, line, "SALES_REPORT", doc.locationFromId(), -qty, owner, deltas);
                settle(doc, line, "SALE", +1);
                settleOwner(doc, line, "CONSIGN_SALE", -1, skuAlbums);
            }
            case CONSIGN_RECALL -> {
                inv(doc, line, "CONSIGN_RECALL_OUT", doc.locationFromId(), -qty, owner, deltas);
                inv(doc, line, "CONSIGN_RECALL_IN", doc.locationToId(), qty, owner, deltas);
            }
            case TRANSFER -> {
                inv(doc, line, "TRANSFER_OUT", doc.locationFromId(), -qty, owner, deltas);
                inv(doc, line, "TRANSFER_IN", doc.locationToId(), qty, owner, deltas);
            }
            case ADJUST -> inv(doc, line, "ADJUST", doc.locationToId(), qty, owner, deltas);
            case OPENING -> inv(doc, line, "OPENING", doc.locationToId(), qty, owner, deltas);
            case CONSIGN_IN -> inv(doc, line, "CONSIGN_IN", doc.locationToId(), qty, doc.counterpartyId(), deltas);
            case RETURN_TO_OWNER ->
                    inv(doc, line, "RETURN_TO_OWNER", doc.locationFromId(), -qty, doc.counterpartyId(), deltas);
            case DIRECT_SALE -> {
                // 현장 수금이므로 거래처 채권(settle) 없음. 위탁 라인만 기획사몫 발생
                inv(doc, line, "DIRECT_SALE", doc.locationFromId(), -qty, owner, deltas);
                settleOwner(doc, line, "CONSIGN_SALE", -1, skuAlbums);
            }
        }
    }

    private void inv(StockDoc doc, StockDocLine line, String entryType, Long locationId, int qtyDelta,
                     Long ownerPartyId, Map<BalanceKey, Long> deltas) {
        jdbc.sql("""
                insert into inventory_entry (org_id, doc_line_id, entry_type, sku_id, location_id,
                    owner_party_id, qty_delta, occurred_on)
                values (:org, :line, :type, :sku, :loc, :owner, :qty, :on)
                """)
                .param("org", doc.orgId()).param("line", line.id()).param("type", entryType)
                .param("sku", line.skuId()).param("loc", locationId).param("owner", ownerPartyId)
                .param("qty", qtyDelta).param("on", doc.occurredOn())
                .update();
        deltas.merge(new BalanceKey(line.skuId(), locationId, ownerPartyId), (long) qtyDelta, Long::sum);
    }

    /** 정산 엔트리. 부호: 양수 = 채권(상대가 줄 돈), 음수 = 채무 */
    private void settle(StockDoc doc, StockDocLine line, String entryType, int sign) {
        long supply = sign * line.unitPrice() * line.qty();
        long vat = Vat.of(supply);
        jdbc.sql("""
                insert into settlement_entry (org_id, counterparty_id, doc_line_id, entry_type,
                    sku_id, qty, unit_price, supply_amount, vat_amount, amount,
                    settlement_period_id, occurred_on)
                values (:org, :cp, :line, :type, :sku, :qty, :price, :supply, :vat, :amount,
                    :period, :on)
                """)
                .param("org", doc.orgId()).param("cp", doc.counterpartyId()).param("line", line.id())
                .param("type", entryType).param("sku", line.skuId()).param("qty", line.qty())
                .param("price", line.unitPrice()).param("supply", supply).param("vat", vat)
                .param("amount", supply + vat).param("period", doc.restatePeriodId())
                .param("on", doc.occurredOn())
                .update();
    }

    /**
     * 위탁 라인의 기획사 정산 엔트리. 기획사 몫 = 절사(|공급가액| × (1 − 수수료율)), 나머지가 유통사 수수료.
     * 판매는 채무(−), 반품·판매취소는 반대 부호. 수수료 차감 후 금액이라 qty × 단가가 성립하지 않으므로
     * 단가는 기록하지 않는다.
     */
    private void settleOwner(StockDoc doc, StockDocLine line, String entryType, int sign,
                             Map<Long, SkuAlbum> skuAlbums) {
        if (line.ownerPartyId() == null) {
            return;
        }
        BigDecimal rate = skuAlbums.get(line.skuId()).commissionRate();
        long gross = line.unitPrice() * line.qty();
        long portionAbs = BigDecimal.valueOf(Math.abs(gross))
                .multiply(BigDecimal.ONE.subtract(rate))
                .setScale(0, RoundingMode.DOWN)
                .longValueExact();
        long supply = sign * Long.signum(gross) * portionAbs;
        long vat = Vat.of(supply);
        jdbc.sql("""
                insert into settlement_entry (org_id, counterparty_id, doc_line_id, entry_type,
                    sku_id, qty, unit_price, supply_amount, vat_amount, amount,
                    settlement_period_id, occurred_on)
                values (:org, :cp, :line, :type, :sku, :qty, null, :supply, :vat, :amount, :period, :on)
                """)
                .param("org", doc.orgId()).param("cp", line.ownerPartyId()).param("line", line.id())
                .param("type", entryType).param("sku", line.skuId()).param("qty", line.qty())
                .param("supply", supply).param("vat", vat).param("amount", supply + vat)
                .param("period", doc.restatePeriodId()).param("on", doc.occurredOn())
                .update();
    }

    /** 잔량 갱신. 키 정렬 순서로 잠가 데드락 방지, 음수 재고는 여기서 거부 */
    private void applyBalances(Long orgId, Map<BalanceKey, Long> deltas) {
        List<BalanceKey> keys = deltas.keySet().stream()
                .sorted(Comparator.comparing(BalanceKey::skuId).thenComparing(BalanceKey::locationId)
                        .thenComparing(k -> k.ownerPartyId() == null ? 0L : k.ownerPartyId()))
                .toList();
        for (BalanceKey key : keys) {
            long delta = deltas.get(key);
            var existing = jdbc.sql("""
                    select id, qty from stock_balance
                    where org_id = :org and sku_id = :sku and location_id = :loc
                      and coalesce(owner_party_id, 0) = coalesce(:owner, 0)
                    for update
                    """)
                    .param("org", orgId).param("sku", key.skuId()).param("loc", key.locationId())
                    .param("owner", key.ownerPartyId())
                    .query((rs, i) -> new long[] {rs.getLong("id"), rs.getLong("qty")})
                    .optional();
            long current = existing.map(e -> e[1]).orElse(0L);
            long next = current + delta;
            if (next < 0) {
                String pool = key.ownerPartyId() == null ? "" : " [위탁: " + partyName(key.ownerPartyId()) + "]";
                throw new DomainException("재고 부족: %s @ %s%s (현재 %d, 필요 %d)".formatted(
                        skuName(key.skuId()), locationName(key.locationId()), pool, current, -delta));
            }
            if (existing.isPresent()) {
                jdbc.sql("update stock_balance set qty = :qty, updated_at = now() where id = :id")
                        .param("qty", next).param("id", existing.get()[0]).update();
            } else {
                jdbc.sql("""
                        insert into stock_balance (org_id, sku_id, location_id, owner_party_id, qty)
                        values (:org, :sku, :loc, :owner, :qty)
                        """)
                        .param("org", orgId).param("sku", key.skuId()).param("loc", key.locationId())
                        .param("owner", key.ownerPartyId()).param("qty", next)
                        .update();
            }
        }
    }

    private String skuName(Long skuId) {
        return jdbc.sql("select name from sku where id = :id").param("id", skuId).query(String.class).single();
    }

    private String locationName(Long locationId) {
        return jdbc.sql("select name from location where id = :id").param("id", locationId)
                .query(String.class).single();
    }

    private String partyName(Long partyId) {
        return jdbc.sql("select name from party where id = :id").param("id", partyId)
                .query(String.class).single();
    }

    // ── 역분개 ────────────────────────────────────────

    @Transactional
    public StockDoc reverse(Long orgId, Long userId, Long docId, LocalDate occurredOn) {
        StockDoc original = dao.findById(orgId, docId);
        if (!"POSTED".equals(original.status())) {
            throw new DomainException("POSTED 문서만 역분개할 수 있습니다 (현재: " + original.status() + ")");
        }
        if (original.reversalOfDocId() != null) {
            throw new DomainException("역분개 문서는 다시 역분개할 수 없습니다");
        }
        LocalDate on = occurredOn != null ? occurredOn : LocalDate.now();

        StockDoc reversal = new StockDoc(null, orgId,
                docNo.next(orgId, original.docType(), on), original.docType(), "POSTED",
                original.counterpartyId(), original.locationFromId(), original.locationToId(), on,
                null, "역분개: " + original.docNo(), userId, null, java.time.Instant.now(),
                original.id(), null, original.lines().stream()
                        .map(l -> new StockDocLine(null, l.lineNo(), l.skuId(), l.qty(), l.unitPrice(),
                                l.ownerPartyId(), l.note()))
                        .toList());
        Long reversalId = dao.insert(reversal);
        StockDoc saved = dao.findById(orgId, reversalId);
        Map<Integer, Long> newLineIdByNo = new HashMap<>();
        saved.lines().forEach(l -> newLineIdByNo.put(l.lineNo(), l.id()));

        // 원본 재고 엔트리 → 부호 반전 복제
        Map<BalanceKey, Long> deltas = new HashMap<>();
        var invRows = jdbc.sql("""
                select ie.id, ie.entry_type, ie.sku_id, ie.location_id, ie.owner_party_id,
                       ie.qty_delta, l.line_no
                from inventory_entry ie join stock_doc_line l on l.id = ie.doc_line_id
                where l.doc_id = :doc
                """)
                .param("doc", original.id())
                .query((rs, i) -> new Object[] {rs.getLong("id"), rs.getString("entry_type"),
                        rs.getLong("sku_id"), rs.getLong("location_id"), rs.getObject("owner_party_id"),
                        rs.getInt("qty_delta"), rs.getInt("line_no")})
                .list();
        for (Object[] r : invRows) {
            Long ownerPartyId = (Long) r[4];
            int negated = -((int) r[5]);
            jdbc.sql("""
                    insert into inventory_entry (org_id, doc_line_id, entry_type, sku_id, location_id,
                        owner_party_id, qty_delta, occurred_on, reversal_of_id)
                    values (:org, :line, :type, :sku, :loc, :owner, :qty, :on, :revOf)
                    """)
                    .param("org", orgId).param("line", newLineIdByNo.get((int) r[6]))
                    .param("type", (String) r[1]).param("sku", (Long) r[2]).param("loc", (Long) r[3])
                    .param("owner", ownerPartyId).param("qty", negated).param("on", on)
                    .param("revOf", (Long) r[0])
                    .update();
            deltas.merge(new BalanceKey((Long) r[2], (Long) r[3], ownerPartyId), (long) negated, Long::sum);
        }

        // 원본 정산 엔트리 → 부호 반전 복제 (기간 미배정 = 차기 마감에 자연 반영)
        var setRows = jdbc.sql("""
                select se.id, se.entry_type, se.counterparty_id, se.sku_id, se.qty, se.unit_price,
                       se.supply_amount, se.vat_amount, se.amount, l.line_no
                from settlement_entry se join stock_doc_line l on l.id = se.doc_line_id
                where l.doc_id = :doc
                """)
                .param("doc", original.id())
                .query((rs, i) -> new Object[] {rs.getLong("id"), rs.getString("entry_type"),
                        rs.getLong("counterparty_id"), rs.getObject("sku_id"), rs.getObject("qty"),
                        rs.getObject("unit_price"), rs.getLong("supply_amount"), rs.getLong("vat_amount"),
                        rs.getLong("amount"), rs.getInt("line_no")})
                .list();
        for (Object[] r : setRows) {
            Integer qty = (Integer) r[4];
            jdbc.sql("""
                    insert into settlement_entry (org_id, counterparty_id, doc_line_id, entry_type,
                        sku_id, qty, unit_price, supply_amount, vat_amount, amount,
                        settlement_period_id, occurred_on, reversal_of_id)
                    values (:org, :cp, :line, :type, :sku, :qty, :price, :supply, :vat, :amount,
                        null, :on, :revOf)
                    """)
                    .param("org", orgId).param("cp", (Long) r[2]).param("line", newLineIdByNo.get((int) r[9]))
                    .param("type", (String) r[1]).param("sku", (Long) r[3])
                    .param("qty", qty != null ? -qty : null)
                    .param("price", (Long) r[5]).param("supply", -((long) r[6])).param("vat", -((long) r[7]))
                    .param("amount", -((long) r[8])).param("on", on).param("revOf", (Long) r[0])
                    .update();
        }

        applyBalances(orgId, deltas);
        dao.markReversed(original.id(), reversalId);
        return dao.findById(orgId, reversalId);
    }
}
