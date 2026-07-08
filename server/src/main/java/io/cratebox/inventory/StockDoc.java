package io.cratebox.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record StockDoc(
        Long id,
        Long orgId,
        String docNo,
        DocType docType,
        String status,           // DRAFT | POSTED | REVERSED
        Long counterpartyId,
        Long locationFromId,
        Long locationToId,
        LocalDate occurredOn,
        Long restatePeriodId,    // 소급 정정 대상 기간 (null = 차기 이월)
        String memo,
        Long createdBy,
        Instant createdAt,
        Instant postedAt,
        Long reversalOfDocId,
        Long reversedByDocId,
        List<StockDocLine> lines) {

    /** ownerPartyId: null = 자사(사입) 재고, 값 = 위탁 기획사 재고 풀 */
    public record StockDocLine(Long id, int lineNo, Long skuId, int qty, Long unitPrice,
                               Long ownerPartyId, String note) {}
}
