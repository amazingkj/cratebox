package io.cratebox.common;

/** 부가세 10%, 원 미만 절사(음수는 절대값 절사 후 부호 적용). SPEC §3.3 */
public final class Vat {
    private Vat() {}

    public static long of(long supplyAmount) {
        long abs = Math.abs(supplyAmount) / 10;
        return supplyAmount >= 0 ? abs : -abs;
    }
}
