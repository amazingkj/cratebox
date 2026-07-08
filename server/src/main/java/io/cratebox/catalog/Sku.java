package io.cratebox.catalog;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("sku")
public record Sku(
        @Id Long id,
        Long orgId,
        Long albumVersionId,
        String barcode,
        String name,
        long listPrice,
        boolean active) {
}
