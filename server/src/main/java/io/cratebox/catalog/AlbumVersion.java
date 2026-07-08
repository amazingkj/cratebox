package io.cratebox.catalog;

import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("album_version")
public record AlbumVersion(
        @Id Long id,
        Long orgId,
        Long albumId,
        String name,
        LocalDate releaseDate) {
}
