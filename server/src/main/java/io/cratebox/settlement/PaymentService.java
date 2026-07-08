package io.cratebox.settlement;

import io.cratebox.common.DomainException;
import io.cratebox.common.NotFoundException;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 입금(IN, 채권 감소)/지급(OUT, 채무 감소) → 정산 원장 전기. 취소는 역분개 */
@Service
public class PaymentService {

    private final JdbcClient jdbc;

    public PaymentService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Long create(Long orgId, Long userId, Long counterpartyId, String direction,
                       long amount, LocalDate occurredOn, String memo) {
        jdbc.sql("select id from party where id = :id and org_id = :org")
                .param("id", counterpartyId).param("org", orgId)
                .query(Long.class).optional()
                .orElseThrow(() -> new DomainException("상대방이 없습니다: " + counterpartyId));

        Long paymentId = jdbc.sql("""
                insert into payment (org_id, counterparty_id, direction, amount, occurred_on, memo, created_by)
                values (:org, :cp, :dir, :amount, :on, :memo, :by)
                returning id
                """)
                .param("org", orgId).param("cp", counterpartyId).param("dir", direction)
                .param("amount", amount).param("on", occurredOn).param("memo", memo).param("by", userId)
                .query(Long.class).single();

        long signed = "IN".equals(direction) ? -amount : amount;
        jdbc.sql("""
                insert into settlement_entry (org_id, counterparty_id, payment_id, entry_type,
                    supply_amount, vat_amount, amount, occurred_on)
                values (:org, :cp, :pay, :type, 0, 0, :amount, :on)
                """)
                .param("org", orgId).param("cp", counterpartyId).param("pay", paymentId)
                .param("type", "IN".equals(direction) ? "PAYMENT_IN" : "PAYMENT_OUT")
                .param("amount", signed).param("on", occurredOn)
                .update();
        return paymentId;
    }

    @Transactional
    public void reverse(Long orgId, Long paymentId) {
        var row = jdbc.sql("""
                select id, reversed from payment where id = :id and org_id = :org
                """)
                .param("id", paymentId).param("org", orgId)
                .query((rs, i) -> new boolean[] {rs.getBoolean("reversed")})
                .optional()
                .orElseThrow(() -> new NotFoundException("입금/지급 건이 없습니다: " + paymentId));
        if (row[0]) {
            throw new DomainException("이미 취소된 건입니다");
        }
        jdbc.sql("""
                insert into settlement_entry (org_id, counterparty_id, payment_id, entry_type,
                    supply_amount, vat_amount, amount, occurred_on, reversal_of_id)
                select org_id, counterparty_id, payment_id, entry_type, 0, 0, -amount,
                       current_date, id
                from settlement_entry
                where payment_id = :pay and reversal_of_id is null
                """)
                .param("pay", paymentId)
                .update();
        jdbc.sql("update payment set reversed = true where id = :id").param("id", paymentId).update();
    }
}
