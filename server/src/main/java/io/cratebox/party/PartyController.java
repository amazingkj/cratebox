package io.cratebox.party;

import io.cratebox.auth.AppPrincipal;
import io.cratebox.common.DomainException;
import io.cratebox.common.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/parties")
public class PartyController {

    public record PartyRequest(
            @NotBlank @Pattern(regexp = "LABEL|RETAILER") String kind,
            @NotBlank String name,
            String bizRegNo, String contactName, String phone, String email, String memo,
            @Pattern(regexp = "SELL_IN|SELL_THROUGH") String settlementBasis,
            BigDecimal defaultSupplyRate,
            @Pattern(regexp = "CARRY_FORWARD|RESTATE") String lateReturnMode,
            Boolean active) {}

    private final PartyRepository parties;
    private final JdbcClient jdbc;

    public PartyController(PartyRepository parties, JdbcClient jdbc) {
        this.parties = parties;
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Party> list(@AuthenticationPrincipal AppPrincipal p,
                            @RequestParam(required = false) String kind) {
        return kind == null
                ? parties.findByOrgIdOrderByName(p.orgId())
                : parties.findByOrgIdAndKindOrderByName(p.orgId(), kind);
    }

    @PostMapping
    @Transactional
    public Party create(@AuthenticationPrincipal AppPrincipal p, @Valid @RequestBody PartyRequest req) {
        validate(req);
        Party saved = parties.save(toParty(null, p.orgId(), req, null));
        ensureRetailerLocation(p.orgId(), saved);
        return saved;
    }

    @PutMapping("/{id}")
    @Transactional
    public Party update(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long id,
                        @Valid @RequestBody PartyRequest req) {
        Party existing = parties.findByIdAndOrgId(id, p.orgId())
                .orElseThrow(() -> new NotFoundException("거래처가 없습니다: " + id));
        if (!existing.kind().equals(req.kind())) {
            throw new DomainException("거래처 유형은 변경할 수 없습니다");
        }
        validate(req);
        Party saved = parties.save(toParty(id, p.orgId(), req, existing.createdAt()));
        ensureRetailerLocation(p.orgId(), saved);
        return saved;
    }

    private void validate(PartyRequest req) {
        if ("RETAILER".equals(req.kind()) && req.settlementBasis() == null) {
            throw new DomainException("거래처는 정산 기준(SELL_IN/SELL_THROUGH)이 필요합니다");
        }
    }

    private Party toParty(Long id, Long orgId, PartyRequest r, java.time.Instant createdAt) {
        return new Party(id, orgId, r.kind(), r.name(), r.bizRegNo(), r.contactName(), r.phone(),
                r.email(), r.memo(), "RETAILER".equals(r.kind()) ? r.settlementBasis() : null,
                r.defaultSupplyRate(),
                r.lateReturnMode() != null ? r.lateReturnMode() : "CARRY_FORWARD",
                r.active() == null || r.active(), createdAt);
    }

    /** SELL_THROUGH 거래처는 매장 재고 위치가 필요하다. 없으면 생성 */
    private void ensureRetailerLocation(Long orgId, Party party) {
        if (!party.isRetailer() || !"SELL_THROUGH".equals(party.settlementBasis())) {
            return;
        }
        jdbc.sql("""
                insert into location (org_id, kind, name, retailer_party_id)
                values (:org, 'RETAILER', :name, :party)
                on conflict (org_id, retailer_party_id) do update set name = :name
                """)
                .param("org", orgId).param("name", party.name() + " 매장").param("party", party.id())
                .update();
    }
}
