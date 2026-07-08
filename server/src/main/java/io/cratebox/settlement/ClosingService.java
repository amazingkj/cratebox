package io.cratebox.settlement;

import io.cratebox.common.DomainException;
import io.cratebox.common.NotFoundException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 월 마감과 정산서 생성.
 * 마감 = 미배정(period_id IS NULL, occurred_on <= 월말) 정산 엔트리에 기간을 도장 찍고
 * 상대방별 정산서를 생성하는 것. 마감 후 확정된 반품은 미배정 상태로 남아 다음 마감에
 * 자연 포함된다(차기 이월). 소급 정정은 문서에 마감 기간을 지정 → 정산서 version+1 재발행.
 */
@Service
public class ClosingService {

    public record CloseResult(Long periodId, String yearMonth, int stampedEntries, int statements) {}

    private final JdbcClient jdbc;

    public ClosingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public CloseResult close(Long orgId, Long userId, String yearMonth) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(yearMonth);
        } catch (Exception e) {
            throw new DomainException("기간 형식은 YYYY-MM 입니다: " + yearMonth);
        }
        if (ym.isAfter(YearMonth.now())) {
            throw new DomainException("미래 기간은 마감할 수 없습니다: " + yearMonth);
        }
        String latest = jdbc.sql(
                "select max(year_month) from settlement_period where org_id = :org and status = 'CLOSED'")
                .param("org", orgId).query(String.class).optional().orElse(null);
        if (latest != null && yearMonth.compareTo(latest) <= 0) {
            throw new DomainException("이미 " + latest + "까지 마감되었습니다");
        }

        Long periodId = jdbc.sql("""
                insert into settlement_period (org_id, year_month, status, closed_at, closed_by)
                values (:org, :ym, 'CLOSED', now(), :by)
                returning id
                """)
                .param("org", orgId).param("ym", yearMonth).param("by", userId)
                .query(Long.class).single();

        LocalDate lastDay = ym.atEndOfMonth();
        int stamped = jdbc.sql("""
                update settlement_entry set settlement_period_id = :pid
                where org_id = :org and settlement_period_id is null and occurred_on <= :lastDay
                """)
                .param("pid", periodId).param("org", orgId).param("lastDay", lastDay)
                .update();

        recoupAdvances(orgId, periodId, lastDay);

        // 이번 기간에 엔트리가 있거나 잔액이 남아 있는 상대방만 정산서 생성
        List<Long> counterparties = jdbc.sql("""
                select se.counterparty_id
                from settlement_entry se
                join settlement_period sp on sp.id = se.settlement_period_id
                where se.org_id = :org and sp.year_month <= :ym
                group by se.counterparty_id
                having count(*) filter (where sp.year_month = :ym) > 0 or sum(se.amount) <> 0
                """)
                .param("org", orgId).param("ym", yearMonth)
                .query(Long.class).list();

        for (Long cpId : counterparties) {
            generateStatement(orgId, userId, periodId, cpId);
        }
        return new CloseResult(periodId, yearMonth, stamped, counterparties.size());
    }

    /**
     * MG 선급금 회수: 이번 기간에 도장된 해당 앨범의 위탁 정산액(우리가 기획사에 줄 돈)에서
     * 선급금 잔여만큼 차감(ADVANCE_RECOUP, 양수)한다. 오래된 선급금부터.
     * 회수 엔트리는 생성 시점에 이번 기간으로 도장된다. 이후 소급 정정으로 그 기간의 위탁 정산액이
     * 줄어도 회수는 재계산하지 않는다(잔액 합계는 항상 정합, MG가 조금 일찍 회수될 뿐).
     */
    private void recoupAdvances(Long orgId, Long periodId, LocalDate lastDay) {
        record AdvanceRow(Long id, Long labelPartyId, Long albumId, long remaining) {}
        List<AdvanceRow> advances = jdbc.sql("""
                select a.id, a.label_party_id, a.album_id,
                       a.amount - coalesce((select sum(se.amount) from settlement_entry se
                                            where se.advance_id = a.id), 0) as remaining
                from advance a
                where a.org_id = :org
                order by a.paid_on, a.id
                """)
                .param("org", orgId)
                .query((rs, i) -> new AdvanceRow(rs.getLong("id"), rs.getLong("label_party_id"),
                        rs.getLong("album_id"), rs.getLong("remaining")))
                .list();
        // 같은 기획사×앨범에 선급금이 여러 건이면 앞선 건이 회수한 만큼 차감 대상에서 제외
        java.util.Map<String, Long> usedByAlbum = new java.util.HashMap<>();
        for (AdvanceRow a : advances) {
            if (a.remaining() <= 0) {
                continue;
            }
            long payable = jdbc.sql("""
                    select coalesce(-sum(se.amount), 0)
                    from settlement_entry se
                    join sku k on k.id = se.sku_id
                    join album_version v on v.id = k.album_version_id
                    where se.org_id = :org and se.counterparty_id = :label
                      and se.settlement_period_id = :pid
                      and se.entry_type in ('CONSIGN_SALE','CONSIGN_RETURN')
                      and v.album_id = :album
                    """)
                    .param("org", orgId).param("label", a.labelPartyId()).param("pid", periodId)
                    .param("album", a.albumId())
                    .query(Long.class).single();
            String key = a.labelPartyId() + ":" + a.albumId();
            long recoup = Math.min(a.remaining(), payable - usedByAlbum.getOrDefault(key, 0L));
            if (recoup <= 0) {
                continue;
            }
            usedByAlbum.merge(key, recoup, Long::sum);
            jdbc.sql("""
                    insert into settlement_entry (org_id, counterparty_id, advance_id, entry_type,
                        supply_amount, vat_amount, amount, settlement_period_id, occurred_on)
                    values (:org, :cp, :adv, 'ADVANCE_RECOUP', 0, 0, :amount, :pid, :on)
                    """)
                    .param("org", orgId).param("cp", a.labelPartyId()).param("adv", a.id())
                    .param("amount", recoup).param("pid", periodId).param("on", lastDay)
                    .update();
        }
    }

    /** 소급 정정 문서 확정 시(StockDocService) 또는 수동 재발행 시 호출 → version+1 */
    @Transactional
    public Long regenerateStatement(Long orgId, Long userId, Long periodId, Long counterpartyId) {
        jdbc.sql("select id from settlement_period where id = :id and org_id = :org and status = 'CLOSED'")
                .param("id", periodId).param("org", orgId)
                .query(Long.class).optional()
                .orElseThrow(() -> new NotFoundException("마감된 기간이 아닙니다: " + periodId));
        return generateStatement(orgId, userId, periodId, counterpartyId);
    }

    private Long generateStatement(Long orgId, Long userId, Long periodId, Long counterpartyId) {
        String ym = jdbc.sql("select year_month from settlement_period where id = :id")
                .param("id", periodId).query(String.class).single();
        int version = jdbc.sql("""
                select coalesce(max(version), 0) + 1 from statement
                where org_id = :org and period_id = :pid and counterparty_id = :cp
                """)
                .param("org", orgId).param("pid", periodId).param("cp", counterpartyId)
                .query(Integer.class).single();

        long opening = jdbc.sql("""
                select coalesce(sum(se.amount), 0)
                from settlement_entry se
                join settlement_period sp on sp.id = se.settlement_period_id
                where se.org_id = :org and se.counterparty_id = :cp and sp.year_month < :ym
                """)
                .param("org", orgId).param("cp", counterpartyId).param("ym", ym)
                .query(Long.class).single();

        record Sums(long chargeSupply, long chargeVat, long chargeTotal, long paymentTotal,
                    long advanceTotal, long consignCount) {}
        Sums s = jdbc.sql("""
                select coalesce(sum(supply_amount) filter (where doc_line_id is not null), 0) as charge_supply,
                       coalesce(sum(vat_amount)    filter (where doc_line_id is not null), 0) as charge_vat,
                       coalesce(sum(amount)        filter (where doc_line_id is not null), 0) as charge_total,
                       coalesce(sum(amount)        filter (where payment_id is not null), 0) as payment_total,
                       coalesce(sum(amount)        filter (where advance_id is not null), 0) as advance_total,
                       count(*) filter (where entry_type in
                           ('CONSIGN_SALE','CONSIGN_RETURN','ADVANCE_RECOUP')) as consign_count
                from settlement_entry
                where org_id = :org and counterparty_id = :cp and settlement_period_id = :pid
                """)
                .param("org", orgId).param("cp", counterpartyId).param("pid", periodId)
                .query((rs, i) -> new Sums(rs.getLong("charge_supply"), rs.getLong("charge_vat"),
                        rs.getLong("charge_total"), rs.getLong("payment_total"),
                        rs.getLong("advance_total"), rs.getLong("consign_count")))
                .single();

        String partyKind = jdbc.sql("select kind from party where id = :id").param("id", counterpartyId)
                .query(String.class).single();
        String kind = "RETAILER".equals(partyKind) ? "RETAILER"
                : (s.consignCount() > 0 ? "LABEL_CONSIGN" : "LABEL_PURCHASE");

        Long statementId = jdbc.sql("""
                insert into statement (org_id, period_id, counterparty_id, kind, version,
                    opening_balance, charge_supply, charge_vat, charge_total, payment_total,
                    advance_total, closing_balance, generated_by)
                values (:org, :pid, :cp, :kind, :ver, :opening, :cs, :cv, :ct, :pt, :adv, :closing, :by)
                returning id
                """)
                .param("org", orgId).param("pid", periodId).param("cp", counterpartyId)
                .param("kind", kind).param("ver", version).param("opening", opening)
                .param("cs", s.chargeSupply()).param("cv", s.chargeVat()).param("ct", s.chargeTotal())
                .param("pt", s.paymentTotal()).param("adv", s.advanceTotal())
                .param("closing", opening + s.chargeTotal() + s.paymentTotal() + s.advanceTotal())
                .param("by", userId)
                .query(Long.class).single();

        // 상품 라인: SKU × 엔트리유형 × 단가로 집계 (역분개 자동 상쇄)
        jdbc.sql("""
                insert into statement_line (statement_id, sku_id, label, entry_type, qty, unit_price,
                    supply_amount, vat_amount, amount)
                select :sid, se.sku_id, k.name, se.entry_type, sum(se.qty), se.unit_price,
                       sum(se.supply_amount), sum(se.vat_amount), sum(se.amount)
                from settlement_entry se join sku k on k.id = se.sku_id
                where se.org_id = :org and se.counterparty_id = :cp and se.settlement_period_id = :pid
                  and se.doc_line_id is not null
                group by se.sku_id, k.name, se.entry_type, se.unit_price
                having sum(se.amount) <> 0 or sum(se.qty) <> 0
                order by k.name, se.entry_type
                """)
                .param("sid", statementId).param("org", orgId).param("cp", counterpartyId)
                .param("pid", periodId)
                .update();

        // 입금/지급 라인
        jdbc.sql("""
                insert into statement_line (statement_id, sku_id, label, entry_type, qty, unit_price,
                    supply_amount, vat_amount, amount)
                select :sid, null, case entry_type when 'PAYMENT_IN' then '입금' else '지급' end,
                       entry_type, null, null, 0, 0, sum(amount)
                from settlement_entry
                where org_id = :org and counterparty_id = :cp and settlement_period_id = :pid
                  and payment_id is not null
                group by entry_type
                """)
                .param("sid", statementId).param("org", orgId).param("cp", counterpartyId)
                .param("pid", periodId)
                .update();

        // MG 차감 라인 (선급금별)
        jdbc.sql("""
                insert into statement_line (statement_id, sku_id, label, entry_type, qty, unit_price,
                    supply_amount, vat_amount, amount)
                select :sid, null, 'MG 차감: ' || a.title, se.entry_type, null, null, 0, 0, sum(se.amount)
                from settlement_entry se
                join advance ad on ad.id = se.advance_id
                join album a on a.id = ad.album_id
                where se.org_id = :org and se.counterparty_id = :cp and se.settlement_period_id = :pid
                group by a.title, se.entry_type
                """)
                .param("sid", statementId).param("org", orgId).param("cp", counterpartyId)
                .param("pid", periodId)
                .update();

        return statementId;
    }
}
