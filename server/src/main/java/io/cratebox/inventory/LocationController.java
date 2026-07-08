package io.cratebox.inventory;

import io.cratebox.auth.AppPrincipal;
import io.cratebox.common.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 창고 CRUD. 거래처 매장 위치는 SELL_THROUGH 거래처 저장 시 자동 생성된다. */
@RestController
@RequestMapping("/api/locations")
public class LocationController {

    public record WarehouseRequest(@NotBlank String name, Boolean active) {}

    private final LocationRepository locations;

    public LocationController(LocationRepository locations) {
        this.locations = locations;
    }

    @GetMapping
    public List<Location> list(@AuthenticationPrincipal AppPrincipal p) {
        return locations.findByOrgIdOrderById(p.orgId());
    }

    @PostMapping
    public Location create(@AuthenticationPrincipal AppPrincipal p, @Valid @RequestBody WarehouseRequest r) {
        return locations.save(new Location(null, p.orgId(), "WAREHOUSE", r.name(), null,
                r.active() == null || r.active()));
    }

    @PutMapping("/{id}")
    public Location update(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long id,
                           @Valid @RequestBody WarehouseRequest r) {
        Location loc = locations.findByIdAndOrgId(id, p.orgId())
                .orElseThrow(() -> new NotFoundException("위치가 없습니다: " + id));
        return locations.save(new Location(id, p.orgId(), loc.kind(), r.name(), loc.retailerPartyId(),
                r.active() == null || r.active()));
    }
}
