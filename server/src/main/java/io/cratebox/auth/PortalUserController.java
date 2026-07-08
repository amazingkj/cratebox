package io.cratebox.auth;

import io.cratebox.common.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/** 기획사 포털 계정 발급 (운영자 전용, 기획사당 1계정) */
@RestController
@RequestMapping("/api/parties/{partyId}/portal-user")
public class PortalUserController {

    public record CreateRequest(@NotBlank String username, @NotBlank @Size(min = 8) String password) {}

    public record ResetPasswordRequest(@NotBlank @Size(min = 8) String password) {}

    public record PortalUserView(boolean exists, String username) {}

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;

    public PortalUserController(JdbcClient jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @GetMapping
    public PortalUserView get(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long partyId) {
        return jdbc.sql("select username from app_user where org_id = :org and party_id = :party")
                .param("org", p.orgId()).param("party", partyId)
                .query(String.class).optional()
                .map(u -> new PortalUserView(true, u))
                .orElse(new PortalUserView(false, null));
    }

    @PostMapping
    public PortalUserView create(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long partyId,
                                 @Valid @RequestBody CreateRequest req) {
        String kind = jdbc.sql("select kind from party where id = :id and org_id = :org")
                .param("id", partyId).param("org", p.orgId())
                .query(String.class).optional()
                .orElseThrow(() -> new DomainException("상대방이 없습니다: " + partyId));
        if (!"LABEL".equals(kind)) {
            throw new DomainException("포털 계정은 기획사에만 발급할 수 있습니다");
        }
        if (get(p, partyId).exists()) {
            throw new DomainException("이미 포털 계정이 있습니다");
        }
        String labelName = jdbc.sql("select name from party where id = :id").param("id", partyId)
                .query(String.class).single();
        jdbc.sql("""
                insert into app_user (org_id, username, password_hash, display_name, role, party_id)
                values (:org, :username, :hash, :name, 'LABEL', :party)
                """)
                .param("org", p.orgId()).param("username", req.username())
                .param("hash", encoder.encode(req.password())).param("name", labelName)
                .param("party", partyId)
                .update();
        return new PortalUserView(true, req.username());
    }

    /** 포털 계정 비밀번호 재설정 (운영자가 새 비밀번호를 지정해 전달) */
    @PutMapping("/password")
    public void resetPassword(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long partyId,
                              @Valid @RequestBody ResetPasswordRequest req) {
        int updated = jdbc.sql("""
                update app_user set password_hash = :hash
                where org_id = :org and party_id = :party
                """)
                .param("hash", encoder.encode(req.password()))
                .param("org", p.orgId()).param("party", partyId)
                .update();
        if (updated == 0) {
            throw new DomainException("포털 계정이 없습니다 — 먼저 발급하세요");
        }
    }
}
