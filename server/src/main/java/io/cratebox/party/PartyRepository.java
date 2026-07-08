package io.cratebox.party;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

public interface PartyRepository extends CrudRepository<Party, Long> {
    List<Party> findByOrgIdOrderByName(Long orgId);

    List<Party> findByOrgIdAndKindOrderByName(Long orgId, String kind);

    Optional<Party> findByIdAndOrgId(Long id, Long orgId);
}
