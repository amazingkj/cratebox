package io.cratebox.catalog;

import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("album")
public record Album(
        @Id Long id,
        Long orgId,
        Long labelPartyId,
        String title,
        String artistName,
        LocalDate releaseDate) {
}
