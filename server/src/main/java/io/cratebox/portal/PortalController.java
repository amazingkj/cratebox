package io.cratebox.portal;

import io.cratebox.auth.AppPrincipal;
import io.cratebox.common.JdbcUtils;
import io.cratebox.common.NotFoundException;
import io.cratebox.settings.OrgProfile;
import io.cratebox.settings.OrgProfiles;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 기획사 포털 (읽기전용). LABEL 역할 전용 — 모든 조회는 principal의 기획사(partyId)로 스코프된다.
 * 재고 = 유통사가 보관 중인 자기 소유(위탁) 재고, 정산서/거래내역 = 자기 앞으로 발행된 것.
 */
@RestController
@RequestMapping("/api/portal")
public class PortalController {

    public record Summary(String labelName, long balance, long unstamped) {}

    public record PortalStockRow(String albumTitle, String versionName, String skuName, String barcode,
                                 String locationName, String locationKind, long qty) {}

    public record PortalStatement(Long id, String yearMonth, String kind, int version,
                                  long openingBalance, long chargeSupply, long chargeVat, long chargeTotal,
                                  long paymentTotal, long advanceTotal, long closingBalance,
                                  Instant generatedAt, boolean latest) {}

    public record PortalStatementLine(String label, String entryType, Integer qty, Long unitPrice,
                                      long supplyAmount, long vatAmount, long amount) {}

    public record PortalStatementDetail(PortalStatement header, OrgProfile issuer,
                                        List<PortalStatementLine> lines) {}

    public record PortalLedgerRow(LocalDate occurredOn, String entryType, String skuName,
                                  Integer qty, long amount, String yearMonth, boolean reversal) {}

    private final JdbcClient jdbc;
    private final OrgProfiles orgProfiles;

    public PortalController(JdbcClient jdbc, OrgProfiles orgProfiles) {
        this.jdbc = jdbc;
        this.orgProfiles = orgProfiles;
    }

    @GetMapping("/summary")
    public Summary summary(@AuthenticationPrincipal AppPrincipal p) {
        return jdbc.sql("""
                select pt.name,
                       coalesce(sum(se.amount), 0) as balance,
                       coalesce(sum(se.amount) filter (where se.settlement_period_id is null), 0) as unstamped
                from party pt
                left join settlement_entry se on se.counterparty_id = pt.id
                where pt.id = :party
                group by pt.name
                """)
                .param("party", p.partyId())
                .query((rs, i) -> new Summary(rs.getString(1), rs.getLong(2), rs.getLong(3)))
                .single();
    }

    /** 유통사에 있는 자기 소유(위탁) 재고 */
    @GetMapping("/stock")
    public List<PortalStockRow> stock(@AuthenticationPrincipal AppPrincipal p) {
        return jdbc.sql("""
                select a.title, v.name as version_name, k.name as sku_name, k.barcode,
                       l.name as location_name, l.kind as location_kind, b.qty
                from stock_balance b
                join sku k on k.id = b.sku_id
                join album_version v on v.id = k.album_version_id
                join album a on a.id = v.album_id
                join location l on l.id = b.location_id
                where b.org_id = :org and b.owner_party_id = :party and b.qty <> 0
                order by a.title, v.name, k.name, l.name
                """)
                .param("org", p.orgId()).param("party", p.partyId())
                .query((rs, i) -> new PortalStockRow(rs.getString("title"), rs.getString("version_name"),
                        rs.getString("sku_name"), rs.getString("barcode"), rs.getString("location_name"),
                        rs.getString("location_kind"), rs.getLong("qty")))
                .list();
    }

    private static final String STATEMENT_SELECT = """
            select st.id, sp.year_month, st.kind, st.version, st.opening_balance, st.charge_supply,
                   st.charge_vat, st.charge_total, st.payment_total, st.advance_total,
                   st.closing_balance, st.generated_at,
                   st.version = (select max(v.version) from statement v
                                 where v.period_id = st.period_id
                                   and v.counterparty_id = st.counterparty_id) as latest
            from statement st
            join settlement_period sp on sp.id = st.period_id
            """;

    private static PortalStatement mapStatement(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PortalStatement(rs.getLong("id"), rs.getString("year_month"), rs.getString("kind"),
                rs.getInt("version"), rs.getLong("opening_balance"), rs.getLong("charge_supply"),
                rs.getLong("charge_vat"), rs.getLong("charge_total"), rs.getLong("payment_total"),
                rs.getLong("advance_total"), rs.getLong("closing_balance"),
                JdbcUtils.toInstant(rs.getObject("generated_at", OffsetDateTime.class)),
                rs.getBoolean("latest"));
    }

    @GetMapping("/statements")
    public List<PortalStatement> statements(@AuthenticationPrincipal AppPrincipal p) {
        return jdbc.sql(STATEMENT_SELECT + """
                where st.org_id = :org and st.counterparty_id = :party
                order by sp.year_month desc, st.version desc
                """)
                .param("org", p.orgId()).param("party", p.partyId())
                .query((rs, i) -> mapStatement(rs)).list();
    }

    @GetMapping("/statements/{id}")
    public PortalStatementDetail statement(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long id) {
        PortalStatement header = jdbc.sql(STATEMENT_SELECT
                        + " where st.org_id = :org and st.counterparty_id = :party and st.id = :id")
                .param("org", p.orgId()).param("party", p.partyId()).param("id", id)
                .query((rs, i) -> mapStatement(rs)).optional()
                .orElseThrow(() -> new NotFoundException("정산서가 없습니다: " + id));
        List<PortalStatementLine> lines = jdbc.sql("""
                select label, entry_type, qty, unit_price, supply_amount, vat_amount, amount
                from statement_line where statement_id = :id order by id
                """)
                .param("id", id)
                .query((rs, i) -> new PortalStatementLine(rs.getString("label"), rs.getString("entry_type"),
                        (Integer) rs.getObject("qty"), (Long) rs.getObject("unit_price"),
                        rs.getLong("supply_amount"), rs.getLong("vat_amount"), rs.getLong("amount")))
                .list();
        return new PortalStatementDetail(header, orgProfiles.get(p.orgId()), lines);
    }

    /** 자기 앞 정산 원장 (판매·반품·지급·MG 회수 내역) */
    @GetMapping("/ledger")
    public List<PortalLedgerRow> ledger(@AuthenticationPrincipal AppPrincipal p) {
        return jdbc.sql("""
                select se.occurred_on, se.entry_type, k.name as sku_name, se.qty, se.amount,
                       sp.year_month, se.reversal_of_id is not null as reversal
                from settlement_entry se
                left join sku k on k.id = se.sku_id
                left join settlement_period sp on sp.id = se.settlement_period_id
                where se.org_id = :org and se.counterparty_id = :party
                order by se.id desc limit 500
                """)
                .param("org", p.orgId()).param("party", p.partyId())
                .query((rs, i) -> new PortalLedgerRow(rs.getObject("occurred_on", LocalDate.class),
                        rs.getString("entry_type"), rs.getString("sku_name"), (Integer) rs.getObject("qty"),
                        rs.getLong("amount"), rs.getString("year_month"), rs.getBoolean("reversal")))
                .list();
    }
}
