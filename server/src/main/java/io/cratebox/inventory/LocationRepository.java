package io.cratebox.inventory;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

public interface LocationRepository extends CrudRepository<Location, Long> {
    List<Location> findByOrgIdOrderById(Long orgId);

    Optional<Location> findByIdAndOrgId(Long id, Long orgId);

    Optional<Location> findByOrgIdAndRetailerPartyId(Long orgId, Long retailerPartyId);
}
