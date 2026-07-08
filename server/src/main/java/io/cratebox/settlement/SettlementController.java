package io.cratebox.settlement;

import io.cratebox.auth.AppPrincipal;
import io.cratebox.common.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settlement")
public class SettlementController {

    public record CloseRequest(@NotBlank String yearMonth) {}

    public record PeriodView(Long id, String yearMonth, String status, Instant closedAt) {}

    public record StatementSummary(Long id, Long periodId, String yearMonth, Long counterpartyId,
                                   String counterpartyName, String kind, int version, long openingBalance,
                                   long chargeSupply, long chargeVat, long chargeTotal, long paymentTotal,
                                   long advanceTotal, long closingBalance, Instant generatedAt, boolean latest) {}

    public record StatementLineView(Long skuId, String label, String entryType, Integer qty,
                                    Long unitPrice, long supplyAmount, long vatAmount, long amount) {}

    public record StatementDetail(StatementSummary header, List<StatementLineView> lines) {}

    public record PaymentRequest(@NotNull Long counterpartyId,
                                 @NotBlank @jakarta.validation.constraints.Pattern(regexp = "IN|OUT") String direction,
                                 @jakarta.validation.constraints.Positive long amount,
                                 @NotNull LocalDate occurredOn, String memo) {}

    public record PaymentView(Long id, Long counterpartyId, String counterpartyName, String direction,
                              long amount, LocalDate occurredOn, String memo, boolean reversed) {}

    private final ClosingService closingService;
    private final PaymentService paymentService;
    private final JdbcClient jdbc;

    public SettlementController(ClosingService closingService, PaymentService paymentService, JdbcClient jdbc) {
        this.closingService = closingService;
        this.paymentService = paymentService;
        this.jdbc = jdbc;
    }

    // ── 기간/마감 ─────────────────────────────────────

    @GetMapping("/periods")
    public List<PeriodView> periods(@AuthenticationPrincipal AppPrincipal p) {
        return jdbc.sql("""
                select id, year_month, status, closed_at from settlement_period
                where org_id = :org order by year_month desc
                """)
                .param("org", p.orgId())
                .query((rs, i) -> new PeriodView(rs.getLong("id"), rs.getString("year_month"),
                        rs.getString("status"),
                        io.cratebox.common.JdbcUtils.toInstant(
                                rs.getObject("closed_at", java.time.OffsetDateTime.class))))
                .list();
    }

    @PostMapping("/close")
    public ClosingService.CloseResult close(@AuthenticationPrincipal AppPrincipal p,
                                            @Valid @RequestBody CloseRequest req) {
        return closingService.close(p.orgId(), p.userId(), req.yearMonth());
    }

    // ── 정산서 ────────────────────────────────────────

    private static final String STATEMENT_SELECT = """
            select st.id, st.period_id, sp.year_month, st.counterparty_id, pt.name as cp_name,
                   st.kind, st.version, st.opening_balance, st.charge_supply, st.charge_vat,
                   st.charge_total, st.payment_total, st.advance_total, st.closing_balance, st.generated_at,
                   st.version = (select max(v.version) from statement v
                                 where v.period_id = st.period_id
                                   and v.counterparty_id = st.counterparty_id) as latest
            from statement st
            join settlement_period sp on sp.id = st.period_id
            join party pt on pt.id = st.counterparty_id
            """;

    private static StatementSummary mapStatement(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StatementSummary(rs.getLong("id"), rs.getLong("period_id"), rs.getString("year_month"),
                rs.getLong("counterparty_id"), rs.getString("cp_name"), rs.getString("kind"),
                rs.getInt("version"), rs.getLong("opening_balance"), rs.getLong("charge_supply"),
                rs.getLong("charge_vat"), rs.getLong("charge_total"), rs.getLong("payment_total"),
                rs.getLong("advance_total"), rs.getLong("closing_balance"),
                io.cratebox.common.JdbcUtils.toInstant(
                        rs.getObject("generated_at", java.time.OffsetDateTime.class)),
                rs.getBoolean("latest"));
    }

    @GetMapping("/statements")
    public List<StatementSummary> statements(@AuthenticationPrincipal AppPrincipal p,
                                             @RequestParam(required = false) String yearMonth,
                                             @RequestParam(required = false) Long counterpartyId) {
        return jdbc.sql(STATEMENT_SELECT + """
                where st.org_id = :org
                  and (:ym::text is null or sp.year_month = :ym)
                  and (:cp::bigint is null or st.counterparty_id = :cp)
                order by sp.year_month desc, pt.name, st.version desc
                """)
                .param("org", p.orgId()).param("ym", yearMonth).param("cp", counterpartyId)
                .query((rs, i) -> mapStatement(rs)).list();
    }

    @GetMapping("/statements/{id}")
    public StatementDetail statement(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long id) {
        StatementSummary header = jdbc.sql(STATEMENT_SELECT + " where st.org_id = :org and st.id = :id")
                .param("org", p.orgId()).param("id", id)
                .query((rs, i) -> mapStatement(rs)).optional()
                .orElseThrow(() -> new NotFoundException("정산서가 없습니다: " + id));
        List<StatementLineView> lines = jdbc.sql("""
                select sku_id, label, entry_type, qty, unit_price, supply_amount, vat_amount, amount
                from statement_line where statement_id = :id order by id
                """)
                .param("id", id)
                .query((rs, i) -> new StatementLineView((Long) rs.getObject("sku_id"), rs.getString("label"),
                        rs.getString("entry_type"), (Integer) rs.getObject("qty"),
                        (Long) rs.getObject("unit_price"), rs.getLong("supply_amount"),
                        rs.getLong("vat_amount"), rs.getLong("amount")))
                .list();
        return new StatementDetail(header, lines);
    }

    @PostMapping("/statements/regenerate")
    public StatementDetail regenerate(@AuthenticationPrincipal AppPrincipal p,
                                      @RequestParam Long periodId, @RequestParam Long counterpartyId) {
        Long id = closingService.regenerateStatement(p.orgId(), p.userId(), periodId, counterpartyId);
        return statement(p, id);
    }

    // ── 입금/지급 ─────────────────────────────────────

    @GetMapping("/payments")
    public List<PaymentView> payments(@AuthenticationPrincipal AppPrincipal p,
                                      @RequestParam(required = false) Long counterpartyId) {
        return jdbc.sql("""
                select pm.id, pm.counterparty_id, pt.name as cp_name, pm.direction, pm.amount,
                       pm.occurred_on, pm.memo, pm.reversed
                from payment pm join party pt on pt.id = pm.counterparty_id
                where pm.org_id = :org and (:cp::bigint is null or pm.counterparty_id = :cp)
                order by pm.id desc limit 500
                """)
                .param("org", p.orgId()).param("cp", counterpartyId)
                .query((rs, i) -> new PaymentView(rs.getLong("id"), rs.getLong("counterparty_id"),
                        rs.getString("cp_name"), rs.getString("direction"), rs.getLong("amount"),
                        rs.getObject("occurred_on", LocalDate.class), rs.getString("memo"),
                        rs.getBoolean("reversed")))
                .list();
    }

    @PostMapping("/payments")
    public java.util.Map<String, Long> createPayment(@AuthenticationPrincipal AppPrincipal p,
                                                     @Valid @RequestBody PaymentRequest req) {
        Long id = paymentService.create(p.orgId(), p.userId(), req.counterpartyId(), req.direction(),
                req.amount(), req.occurredOn(), req.memo());
        return java.util.Map.of("id", id);
    }

    @PostMapping("/payments/{id}/reverse")
    public void reversePayment(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long id) {
        paymentService.reverse(p.orgId(), id);
    }
}
