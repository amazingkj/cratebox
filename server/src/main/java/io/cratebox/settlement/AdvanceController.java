package io.cratebox.settlement;

import io.cratebox.auth.AppPrincipal;
import io.cratebox.common.DomainException;
import io.cratebox.common.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * MG 선급금. 지급액은 정산 잔액 밖에서 관리되고(별도 지급으로 기록하지 말 것),
 * 마감 시 해당 앨범의 위탁 정산액에서 자동 차감(ADVANCE_RECOUP)된다.
 */
@RestController
@RequestMapping("/api/advances")
public class AdvanceController {

    public record AdvanceRequest(@NotNull Long labelPartyId, @NotNull Long albumId,
                                 @Positive long amount, @NotNull LocalDate paidOn, String memo) {}

    public record AdvanceView(Long id, Long labelPartyId, String labelName, Long albumId,
                              String albumTitle, long amount, long recouped, long remaining,
                              LocalDate paidOn, String memo) {}

    private final JdbcClient jdbc;

    public AdvanceController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<AdvanceView> list(@AuthenticationPrincipal AppPrincipal p) {
        return jdbc.sql("""
                select ad.id, ad.label_party_id, pt.name as label_name, ad.album_id, a.title,
                       ad.amount, ad.paid_on, ad.memo,
                       coalesce((select sum(se.amount) from settlement_entry se
                                 where se.advance_id = ad.id), 0) as recouped
                from advance ad
                join party pt on pt.id = ad.label_party_id
                join album a on a.id = ad.album_id
                where ad.org_id = :org
                order by ad.paid_on desc, ad.id desc
                """)
                .param("org", p.orgId())
                .query((rs, i) -> {
                    long amount = rs.getLong("amount");
                    long recouped = rs.getLong("recouped");
                    return new AdvanceView(rs.getLong("id"), rs.getLong("label_party_id"),
                            rs.getString("label_name"), rs.getLong("album_id"), rs.getString("title"),
                            amount, recouped, amount - recouped,
                            rs.getObject("paid_on", LocalDate.class), rs.getString("memo"));
                })
                .list();
    }

    @PostMapping
    public Map<String, Long> create(@AuthenticationPrincipal AppPrincipal p,
                                    @Valid @RequestBody AdvanceRequest req) {
        Long labelOfAlbum = jdbc.sql("select label_party_id from album where id = :id and org_id = :org")
                .param("id", req.albumId()).param("org", p.orgId())
                .query(Long.class).optional()
                .orElseThrow(() -> new DomainException("앨범이 없습니다: " + req.albumId()));
        if (!labelOfAlbum.equals(req.labelPartyId())) {
            throw new DomainException("해당 기획사의 앨범이 아닙니다");
        }
        jdbc.sql("""
                select id from trade_agreement
                where org_id = :org and album_id = :album and kind = 'CONSIGNMENT'
                """)
                .param("org", p.orgId()).param("album", req.albumId())
                .query(Long.class).optional()
                .orElseThrow(() -> new DomainException(
                        "위탁 계약이 등록된 앨범만 MG를 지급할 수 있습니다 — 상품 화면에서 거래 계약을 먼저 등록하세요"));
        Long id = jdbc.sql("""
                insert into advance (org_id, label_party_id, album_id, amount, paid_on, memo, created_by)
                values (:org, :label, :album, :amount, :on, :memo, :by)
                returning id
                """)
                .param("org", p.orgId()).param("label", req.labelPartyId()).param("album", req.albumId())
                .param("amount", req.amount()).param("on", req.paidOn()).param("memo", req.memo())
                .param("by", p.userId())
                .query(Long.class).single();
        return Map.of("id", id);
    }

    @DeleteMapping("/{id}")
    public void delete(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long id) {
        Long recoups = jdbc.sql("select count(*) from settlement_entry where advance_id = :id")
                .param("id", id).query(Long.class).single();
        if (recoups > 0) {
            throw new DomainException("이미 회수가 시작된 선급금은 삭제할 수 없습니다");
        }
        int n = jdbc.sql("delete from advance where id = :id and org_id = :org")
                .param("id", id).param("org", p.orgId()).update();
        if (n == 0) {
            throw new NotFoundException("선급금이 없습니다: " + id);
        }
    }
}
