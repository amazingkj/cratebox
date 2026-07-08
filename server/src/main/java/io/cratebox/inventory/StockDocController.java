package io.cratebox.inventory;

import io.cratebox.auth.AppPrincipal;
import io.cratebox.inventory.StockDoc.StockDocLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/docs")
public class StockDocController {

    public record LineRequest(@NotNull Long skuId, int qty, Long unitPrice, Long ownerPartyId, String note) {}

    public record DocRequest(@NotNull DocType docType, Long counterpartyId,
                             Long locationFromId, Long locationToId,
                             @NotNull LocalDate occurredOn, Long restatePeriodId, String memo,
                             @NotEmpty List<LineRequest> lines) {}

    public record ReverseRequest(LocalDate occurredOn) {}

    private final StockDocService service;
    private final StockDocDao dao;

    public StockDocController(StockDocService service, StockDocDao dao) {
        this.service = service;
        this.dao = dao;
    }

    @GetMapping
    public List<StockDocDao.DocSummary> list(@AuthenticationPrincipal AppPrincipal p,
                                             @RequestParam(required = false) String docType,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to) {
        return dao.list(p.orgId(), docType, status, from, to);
    }

    @GetMapping("/{id}")
    public StockDoc get(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long id) {
        return dao.findById(p.orgId(), id);
    }

    @PostMapping
    public StockDoc create(@AuthenticationPrincipal AppPrincipal p, @Valid @RequestBody DocRequest req) {
        return service.createDraft(p.orgId(), p.userId(), toDoc(req));
    }

    @PutMapping("/{id}")
    public StockDoc update(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long id,
                           @Valid @RequestBody DocRequest req) {
        return service.updateDraft(p.orgId(), id, toDoc(req));
    }

    @DeleteMapping("/{id}")
    public void delete(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long id) {
        dao.deleteDraft(p.orgId(), id);
    }

    @PostMapping("/{id}/post")
    public StockDoc post(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long id) {
        return service.post(p.orgId(), p.userId(), id);
    }

    @PostMapping("/{id}/reverse")
    public StockDoc reverse(@AuthenticationPrincipal AppPrincipal p, @PathVariable Long id,
                            @RequestBody(required = false) ReverseRequest req) {
        return service.reverse(p.orgId(), p.userId(), id, req != null ? req.occurredOn() : null);
    }

    private StockDoc toDoc(DocRequest r) {
        List<StockDocLine> lines = r.lines().stream()
                .map(l -> new StockDocLine(null, 0, l.skuId(), l.qty(), l.unitPrice(), l.ownerPartyId(), l.note()))
                .toList();
        return new StockDoc(null, null, null, r.docType(), "DRAFT", r.counterpartyId(),
                r.locationFromId(), r.locationToId(), r.occurredOn(), r.restatePeriodId(), r.memo(),
                null, null, null, null, null, lines);
    }
}
