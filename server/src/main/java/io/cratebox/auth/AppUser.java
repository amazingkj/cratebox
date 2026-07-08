package io.cratebox.auth;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

@Table("app_user")
public record AppUser(
        @Id Long id,
        Long orgId,
        String username,
        String passwordHash,
        String displayName,
        String role,        // ADMIN | LABEL
        Long partyId,       // LABEL 사용자의 기획사 (ADMIN은 null)
        @ReadOnlyProperty Instant createdAt) {   // DB default now()
}
