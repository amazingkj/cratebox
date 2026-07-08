package io.cratebox.catalog;

import io.cratebox.auth.AppPrincipal;
import io.cratebox.common.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CatalogController {

    public record AlbumRequest(@NotNull Long labelPartyId, @NotBlank String title,
                               @NotBlank String artistName, LocalDate releaseDate) {}

    public record VersionRequest(@NotBlank String name, LocalDate releaseDate) {}

    public record SkuRequest(@NotBlank String barcode, @NotBlank String name,
                             @PositiveOrZero long listPrice, Boolean active) {}

    public record SkuFlat(Long id, String name, String barcode, long listPrice, boolean active,
                          Long albumVersionId, String versionName, Long albumId, String albumTitle,
                          String artistName, Long labelPartyId, String labelName, String agreementKind) {}

    private final AlbumRepository albums;
    private final AlbumVersionRepository versions;
    private final SkuRepository skus;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    public CatalogController(AlbumRepository albums, AlbumVersionRepository versions, SkuRepository skus,
                             org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.albums = albums;
        this.versions = versions;
        this.skus = skus;
        this.jdbc = jdbc;
    }

    /** 문서 작성용 flat SKU 목록 */
    @GetMapping("/skus")
    public List<SkuFlat> allSkus(@AuthenticationPrincipal AppPrincipal p) {
        return jdbc.sql("""
                select k.id, k.name, k.barcode, k.list_price, k.active,
                       v.id as version_id, v.name as version_name,
                       a.id as album_id, a.title, a.artist_name,
                       a.label_party_id, pt.name as label_name, ta.kind as agreement_kind
                from sku k
                join album_version v on v.id = k.album_version_id
                join album a on a.id = v.album_id
                join party pt on pt.id = a.label_party_id
                left join trade_agreement ta on ta.album_id = a.id
                where k.org_id = :org
                order by a.title, v.name, k.name
                """)
                .param("org", p.orgId())
                .query((rs, i) -> new SkuFlat(rs.getLong("id"), rs.getString("name"), rs.getString("barcode"),
                        rs.getLong("list_price"), rs.getBoolean("active"), rs.getLong("version_id"),
                        rs.getString("version_name"), rs.getLong("album_id"), rs.getString("title"),
                        rs.getString("artist_name"), rs.getLong("label_party_id"),
                        rs.getString("label_name"), rs.getString("agreement_kind")))
                .list();
    }

    // ── 앨범 ──────────────────────────────────────────

    @GetMapping("/albums")
    public List<Album> listAlbums(@AuthenticationPrincipal AppPrincipal p) {
        return albums.findByOrgIdOrderByTitle(p.orgId());
    }

    @PostMapping("/albums")
    public Album createAlbum(@AuthenticationPrincipal AppPrincipal p, @Valid @RequestBody AlbumRequest r) {
        return albums.save(new Album(null, p.orgId(), r.labelPartyId(), r.title(), r.artistName(), r.releaseDate()));
    }

    @PutMapping("/albums/{id}")
    public Album updateAlbum(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long id,
                             @Valid @RequestBody AlbumRequest r) {
        requireAlbum(p, id);
        return albums.save(new Album(id, p.orgId(), r.labelPartyId(), r.title(), r.artistName(), r.releaseDate()));
    }

    // ── 버전 ──────────────────────────────────────────

    @GetMapping("/albums/{albumId}/versions")
    public List<AlbumVersion> listVersions(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long albumId) {
        requireAlbum(p, albumId);
        return versions.findByAlbumIdOrderById(albumId);
    }

    @PostMapping("/albums/{albumId}/versions")
    public AlbumVersion createVersion(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long albumId,
                                      @Valid @RequestBody VersionRequest r) {
        requireAlbum(p, albumId);
        return versions.save(new AlbumVersion(null, p.orgId(), albumId, r.name(), r.releaseDate()));
    }

    @PutMapping("/versions/{id}")
    public AlbumVersion updateVersion(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long id,
                                      @Valid @RequestBody VersionRequest r) {
        AlbumVersion v = versions.findByIdAndOrgId(id, p.orgId())
                .orElseThrow(() -> new NotFoundException("버전이 없습니다: " + id));
        return versions.save(new AlbumVersion(id, p.orgId(), v.albumId(), r.name(), r.releaseDate()));
    }

    // ── SKU ───────────────────────────────────────────

    @GetMapping("/versions/{versionId}/skus")
    public List<Sku> listSkus(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long versionId) {
        versions.findByIdAndOrgId(versionId, p.orgId())
                .orElseThrow(() -> new NotFoundException("버전이 없습니다: " + versionId));
        return skus.findByAlbumVersionIdOrderById(versionId);
    }

    @PostMapping("/versions/{versionId}/skus")
    public Sku createSku(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long versionId,
                         @Valid @RequestBody SkuRequest r) {
        versions.findByIdAndOrgId(versionId, p.orgId())
                .orElseThrow(() -> new NotFoundException("버전이 없습니다: " + versionId));
        return skus.save(new Sku(null, p.orgId(), versionId, r.barcode(), r.name(), r.listPrice(),
                r.active() == null || r.active()));
    }

    @PutMapping("/skus/{id}")
    public Sku updateSku(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long id,
                         @Valid @RequestBody SkuRequest r) {
        Sku s = skus.findByIdAndOrgId(id, p.orgId())
                .orElseThrow(() -> new NotFoundException("SKU가 없습니다: " + id));
        return skus.save(new Sku(id, p.orgId(), s.albumVersionId(), r.barcode(), r.name(), r.listPrice(),
                r.active() == null || r.active()));
    }

    private void requireAlbum(AppPrincipal p, Long albumId) {
        albums.findByIdAndOrgId(albumId, p.orgId())
                .orElseThrow(() -> new NotFoundException("앨범이 없습니다: " + albumId));
    }
}
