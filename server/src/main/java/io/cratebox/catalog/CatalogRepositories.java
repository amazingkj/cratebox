package io.cratebox.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

interface AlbumRepository extends CrudRepository<Album, Long> {
    List<Album> findByOrgIdOrderByTitle(Long orgId);

    Optional<Album> findByIdAndOrgId(Long id, Long orgId);
}

interface AlbumVersionRepository extends CrudRepository<AlbumVersion, Long> {
    List<AlbumVersion> findByAlbumIdOrderById(Long albumId);

    Optional<AlbumVersion> findByIdAndOrgId(Long id, Long orgId);
}

interface SkuRepository extends CrudRepository<Sku, Long> {
    List<Sku> findByAlbumVersionIdOrderById(Long albumVersionId);

    Optional<Sku> findByIdAndOrgId(Long id, Long orgId);
}
