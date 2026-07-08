package io.cratebox.party;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

@Table("party")
public record Party(
        @Id Long id,
        Long orgId,
        String kind,              // LABEL | RETAILER
        String name,
        String bizRegNo,
        String contactName,
        String phone,
        String email,
        String memo,
        String settlementBasis,   // RETAILER: SELL_IN | SELL_THROUGH
        BigDecimal defaultSupplyRate,
        String lateReturnMode,    // CARRY_FORWARD | RESTATE
        boolean active,
        @ReadOnlyProperty Instant createdAt) {   // DB default now()

    public boolean isRetailer() {
        return "RETAILER".equals(kind);
    }

    public boolean isLabel() {
        return "LABEL".equals(kind);
    }
}
