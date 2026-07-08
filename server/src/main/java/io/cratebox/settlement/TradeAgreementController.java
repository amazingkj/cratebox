package io.cratebox.settlement;

import io.cratebox.auth.AppPrincipal;
import io.cratebox.common.DomainException;
import io.cratebox.common.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 거래 계약: 앨범 단위 사입/위탁 구분과 위탁 수수료율. 위탁 라인 전기의 전제 조건 */
@RestController
@RequestMapping("/api")
public class TradeAgreementController {

    public record AgreementRequest(@NotBlank String kind, BigDecimal commissionRate, String memo) {}

    public record AgreementView(Long albumId, String albumTitle, Long labelPartyId, String labelName,
                                String kind, BigDecimal commissionRate, String memo) {}

    private final JdbcClient jdbc;

    public TradeAgreementController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/agreements")
    public List<AgreementView> list(@AuthenticationPrincipal AppPrincipal p) {
        return jdbc.sql("""
                select ta.album_id, a.title, a.label_party_id, pt.name as label_name,
                       ta.kind, ta.commission_rate, ta.memo
                from trade_agreement ta
                join album a on a.id = ta.album_id
                join party pt on pt.id = a.label_party_id
                where ta.org_id = :org
                order by a.title
                """)
                .param("org", p.orgId())
                .query((rs, i) -> new AgreementView(rs.getLong("album_id"), rs.getString("title"),
                        rs.getLong("label_party_id"), rs.getString("label_name"), rs.getString("kind"),
                        rs.getBigDecimal("commission_rate"), rs.getString("memo")))
                .list();
    }

    @PutMapping("/albums/{albumId}/agreement")
    public void upsert(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long albumId,
                       @Valid @RequestBody AgreementRequest req) {
        if (!"PURCHASE".equals(req.kind()) && !"CONSIGNMENT".equals(req.kind())) {
            throw new DomainException("계약 종류는 PURCHASE 또는 CONSIGNMENT 입니다");
        }
        if ("CONSIGNMENT".equals(req.kind())) {
            if (req.commissionRate() == null || req.commissionRate().signum() < 0
                    || req.commissionRate().compareTo(BigDecimal.ONE) > 0) {
                throw new DomainException("위탁 계약에는 0~1 사이의 수수료율이 필요합니다");
            }
        }
        requireAlbum(p, albumId);
        jdbc.sql("""
                insert into trade_agreement (org_id, album_id, kind, commission_rate, memo)
                values (:org, :album, :kind, :rate, :memo)
                on conflict (org_id, album_id)
                do update set kind = :kind, commission_rate = :rate, memo = :memo
                """)
                .param("org", p.orgId()).param("album", albumId).param("kind", req.kind())
                .param("rate", "CONSIGNMENT".equals(req.kind()) ? req.commissionRate() : null)
                .param("memo", req.memo())
                .update();
    }

    @DeleteMapping("/albums/{albumId}/agreement")
    public void delete(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long albumId) {
        requireAlbum(p, albumId);
        jdbc.sql("delete from trade_agreement where org_id = :org and album_id = :album")
                .param("org", p.orgId()).param("album", albumId).update();
    }

    private void requireAlbum(AppPrincipal p, Long albumId) {
        jdbc.sql("select id from album where id = :id and org_id = :org")
                .param("id", albumId).param("org", p.orgId())
                .query(Long.class).optional()
                .orElseThrow(() -> new NotFoundException("앨범이 없습니다: " + albumId));
    }
}
