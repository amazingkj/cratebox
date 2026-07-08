package io.cratebox.report;

import io.cratebox.auth.AppPrincipal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    public record StockRow(Long skuId, String skuName, String barcode, String albumTitle,
                           String versionName, Long locationId, String locationName, String locationKind,
                           Long ownerPartyId, String ownerName, long qty) {}

    public record FirstWeekRow(Long albumVersionId, String albumTitle, String versionName,
                               LocalDate releaseDate, LocalDate windowEnd, long units) {}

    public record BalanceRow(Long counterpartyId, String name, String kind, long balance,
                             long unstamped) {}

    public record LedgerRow(Long id, LocalDate occurredOn, String entryType, String docNo,
                            Long skuId, String skuName, Integer qty, Long unitPrice,
                            Long supplyAmount, Long vatAmount, Long amount, String yearMonth,
                            boolean reversal) {}

    public record InvLedgerRow(Long id, LocalDate occurredOn, String entryType, String docNo,
                               Long skuId, String skuName, String locationName, String ownerName,
                               int qtyDelta, boolean reversal) {}

    private final JdbcClient jdbc;

    public ReportController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 재고 현황: 위치별 잔량 (거래처 매장 = 판매분 거래처의 미판매·회수 대상 재고) */
    @GetMapping("/stock")
    public List<StockRow> stock(@AuthenticationPrincipal AppPrincipal p,
                                @RequestParam(required = false) Long locationId,
                                @RequestParam(required = false) Long skuId) {
        return jdbc.sql("""
                select b.sku_id, k.name as sku_name, k.barcode, a.title, v.name as version_name,
                       b.location_id, l.name as location_name, l.kind as location_kind,
                       b.owner_party_id, op.name as owner_name, b.qty
                from stock_balance b
                join sku k on k.id = b.sku_id
                join album_version v on v.id = k.album_version_id
                join album a on a.id = v.album_id
                join location l on l.id = b.location_id
                left join party op on op.id = b.owner_party_id
                where b.org_id = :org and b.qty <> 0
                  and (:loc::bigint is null or b.location_id = :loc)
                  and (:sku::bigint is null or b.sku_id = :sku)
                order by a.title, v.name, k.name, l.name, op.name nulls first
                """)
                .param("org", p.orgId()).param("loc", locationId).param("sku", skuId)
                .query((rs, i) -> new StockRow(rs.getLong("sku_id"), rs.getString("sku_name"),
                        rs.getString("barcode"), rs.getString("title"), rs.getString("version_name"),
                        rs.getLong("location_id"), rs.getString("location_name"),
                        rs.getString("location_kind"), (Long) rs.getObject("owner_party_id"),
                        rs.getString("owner_name"), rs.getLong("qty")))
                .list();
    }

    /** 초동: 발매일 포함 7일간 순출하량(출고+판매보고−거래처반품). 유통사 출고 기준 */
    @GetMapping("/first-week")
    public List<FirstWeekRow> firstWeek(@AuthenticationPrincipal AppPrincipal p) {
        return jdbc.sql("""
                select v.id as version_id, a.title, v.name as version_name,
                       coalesce(v.release_date, a.release_date) as release_date,
                       coalesce(v.release_date, a.release_date) + 6 as window_end,
                       coalesce(sum(-ie.qty_delta), 0) as units
                from album_version v
                join album a on a.id = v.album_id
                left join sku k on k.album_version_id = v.id
                left join inventory_entry ie on ie.sku_id = k.id
                     and ie.entry_type in ('SALE_OUT', 'SALES_REPORT', 'CUSTOMER_RETURN')
                     and ie.occurred_on between coalesce(v.release_date, a.release_date)
                                            and coalesce(v.release_date, a.release_date) + 6
                where v.org_id = :org and coalesce(v.release_date, a.release_date) is not null
                group by v.id, a.title, v.name, coalesce(v.release_date, a.release_date)
                order by release_date desc, a.title
                """)
                .param("org", p.orgId())
                .query((rs, i) -> new FirstWeekRow(rs.getLong("version_id"), rs.getString("title"),
                        rs.getString("version_name"), rs.getObject("release_date", LocalDate.class),
                        rs.getObject("window_end", LocalDate.class), rs.getLong("units")))
                .list();
    }

    /** 상대방별 잔액: 양수 = 미수(받을 돈), 음수 = 미지급(줄 돈). unstamped = 아직 미마감분 */
    @GetMapping("/balances")
    public List<BalanceRow> balances(@AuthenticationPrincipal AppPrincipal p) {
        return jdbc.sql("""
                select pt.id, pt.name, pt.kind, coalesce(sum(se.amount), 0) as balance,
                       coalesce(sum(se.amount) filter (where se.settlement_period_id is null), 0) as unstamped
                from party pt
                left join settlement_entry se on se.counterparty_id = pt.id
                where pt.org_id = :org
                group by pt.id, pt.name, pt.kind
                having coalesce(sum(se.amount), 0) <> 0
                    or coalesce(sum(se.amount) filter (where se.settlement_period_id is null), 0) <> 0
                order by pt.name
                """)
                .param("org", p.orgId())
                .query((rs, i) -> new BalanceRow(rs.getLong("id"), rs.getString("name"),
                        rs.getString("kind"), rs.getLong("balance"), rs.getLong("unstamped")))
                .list();
    }

    /** 정산 원장 조회 */
    @GetMapping("/settlement-ledger")
    public List<LedgerRow> settlementLedger(@AuthenticationPrincipal AppPrincipal p,
                                            @RequestParam Long counterpartyId,
                                            @RequestParam(required = false) LocalDate from,
                                            @RequestParam(required = false) LocalDate to) {
        return jdbc.sql("""
                select se.id, se.occurred_on, se.entry_type, d.doc_no, se.sku_id, k.name as sku_name,
                       se.qty, se.unit_price, se.supply_amount, se.vat_amount, se.amount,
                       sp.year_month, se.reversal_of_id is not null as reversal
                from settlement_entry se
                left join stock_doc_line dl on dl.id = se.doc_line_id
                left join stock_doc d on d.id = dl.doc_id
                left join sku k on k.id = se.sku_id
                left join settlement_period sp on sp.id = se.settlement_period_id
                where se.org_id = :org and se.counterparty_id = :cp
                  and (:from::date is null or se.occurred_on >= :from)
                  and (:to::date is null or se.occurred_on <= :to)
                order by se.id desc limit 500
                """)
                .param("org", p.orgId()).param("cp", counterpartyId).param("from", from).param("to", to)
                .query((rs, i) -> new LedgerRow(rs.getLong("id"), rs.getObject("occurred_on", LocalDate.class),
                        rs.getString("entry_type"), rs.getString("doc_no"), (Long) rs.getObject("sku_id"),
                        rs.getString("sku_name"), (Integer) rs.getObject("qty"),
                        (Long) rs.getObject("unit_price"), (Long) rs.getObject("supply_amount"),
                        (Long) rs.getObject("vat_amount"), (Long) rs.getObject("amount"),
                        rs.getString("year_month"), rs.getBoolean("reversal")))
                .list();
    }

    /** 재고 원장 조회 */
    @GetMapping("/inventory-ledger")
    public List<InvLedgerRow> inventoryLedger(@AuthenticationPrincipal AppPrincipal p,
                                              @RequestParam(required = false) Long skuId,
                                              @RequestParam(required = false) Long locationId,
                                              @RequestParam(required = false) LocalDate from,
                                              @RequestParam(required = false) LocalDate to) {
        return jdbc.sql("""
                select ie.id, ie.occurred_on, ie.entry_type, d.doc_no, ie.sku_id, k.name as sku_name,
                       l.name as location_name, op.name as owner_name, ie.qty_delta,
                       ie.reversal_of_id is not null as reversal
                from inventory_entry ie
                join stock_doc_line dl on dl.id = ie.doc_line_id
                join stock_doc d on d.id = dl.doc_id
                join sku k on k.id = ie.sku_id
                join location l on l.id = ie.location_id
                left join party op on op.id = ie.owner_party_id
                where ie.org_id = :org
                  and (:sku::bigint is null or ie.sku_id = :sku)
                  and (:loc::bigint is null or ie.location_id = :loc)
                  and (:from::date is null or ie.occurred_on >= :from)
                  and (:to::date is null or ie.occurred_on <= :to)
                order by ie.id desc limit 500
                """)
                .param("org", p.orgId()).param("sku", skuId).param("loc", locationId)
                .param("from", from).param("to", to)
                .query((rs, i) -> new InvLedgerRow(rs.getLong("id"), rs.getObject("occurred_on", LocalDate.class),
                        rs.getString("entry_type"), rs.getString("doc_no"), rs.getLong("sku_id"),
                        rs.getString("sku_name"), rs.getString("location_name"), rs.getString("owner_name"),
                        rs.getInt("qty_delta"), rs.getBoolean("reversal")))
                .list();
    }
}
