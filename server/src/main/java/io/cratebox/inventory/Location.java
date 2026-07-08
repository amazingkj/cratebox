package io.cratebox.inventory;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("location")
public record Location(
        @Id Long id,
        Long orgId,
        String kind,            // WAREHOUSE | RETAILER
        String name,
        Long retailerPartyId,   // RETAILER 위치의 소속 거래처
        boolean active) {

    public boolean isWarehouse() {
        return "WAREHOUSE".equals(kind);
    }
}
