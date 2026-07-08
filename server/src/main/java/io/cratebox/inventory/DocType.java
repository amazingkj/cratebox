package io.cratebox.inventory;

/** 문서 타입. 전기 규칙은 docs/DATA-MODEL.md §2 매트릭스 참조 */
public enum DocType {
    PURCHASE_IN("PI"),      // 사입입고 (기획사 → 창고)
    PURCHASE_RETURN("PR"),  // 매입반품 (창고 → 기획사)
    SALE_OUT("SO"),         // 판매출고 (SELL_IN 거래처)
    CONSIGN_PLACE("CP"),    // 진열출고 (창고 → SELL_THROUGH 거래처 매장)
    SALES_REPORT("SR"),     // 판매보고 (SELL_THROUGH 거래처, 음수 라인 = 판매 취소)
    CUSTOMER_RETURN("CR"),  // 거래처반품 (SELL_IN 거래처 → 창고)
    CONSIGN_RECALL("RC"),   // 회수 (SELL_THROUGH 매장 미판매분 → 창고)
    TRANSFER("TF"),         // 창고이동
    ADJUST("AJ"),           // 실사조정 (signed 수량)
    OPENING("OP"),          // 기초재고
    CONSIGN_IN("CI"),       // 수탁입고 (기획사 소유 재고 입고, 정산 없음) — Phase ②
    RETURN_TO_OWNER("RO");  // 위탁 반납 (기획사 소유 재고 반환, 정산 없음) — Phase ②

    public final String prefix;

    DocType(String prefix) {
        this.prefix = prefix;
    }

    /** 정산 원장 엔트리를 생성하는 타입 (단가 필수) */
    public boolean priced() {
        return switch (this) {
            case PURCHASE_IN, PURCHASE_RETURN, SALE_OUT, CUSTOMER_RETURN, SALES_REPORT -> true;
            default -> false;
        };
    }

    /** 음수 수량 라인 허용 여부 */
    public boolean allowsNegativeQty() {
        return this == ADJUST || this == SALES_REPORT;
    }
}
