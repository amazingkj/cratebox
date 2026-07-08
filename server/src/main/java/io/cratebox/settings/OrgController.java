package io.cratebox.settings;

import io.cratebox.auth.AppPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 발행사(운영사) 정보 조회·수정 (운영자 전용) */
@RestController
@RequestMapping("/api/org")
public class OrgController {

    public record UpdateRequest(@NotBlank String name, String bizRegNo, String ceoName,
                                String address, String phone, String email) {}

    private final OrgProfiles profiles;

    public OrgController(OrgProfiles profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public OrgProfile get(@AuthenticationPrincipal AppPrincipal p) {
        return profiles.get(p.orgId());
    }

    @PutMapping
    public OrgProfile update(@AuthenticationPrincipal AppPrincipal p, @Valid @RequestBody UpdateRequest req) {
        profiles.update(p.orgId(), new OrgProfile(req.name(), blank(req.bizRegNo()), blank(req.ceoName()),
                blank(req.address()), blank(req.phone()), blank(req.email())));
        return profiles.get(p.orgId());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
