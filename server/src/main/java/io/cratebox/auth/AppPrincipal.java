package io.cratebox.auth;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** role: ADMIN(유통사 운영자) | LABEL(기획사 포털, partyId = 자기 기획사) */
public record AppPrincipal(Long userId, Long orgId, String username, String displayName,
                           String role, Long partyId, String passwordHash)
        implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }
}
