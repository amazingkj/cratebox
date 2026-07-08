package io.cratebox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * SPEC.md §5 검증 계획의 T1~T8.
 * 한 달치 운영을 실제 API로 수행하고 손으로 계산한 숫자와 전액 대조하는 골든 시나리오.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestFlywayConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GoldenScenarioIT {

    @Autowired TestRestTemplate rest;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper om;

    private String cookie;
    private long label, hottracks, townRecords;
    private long wh1 = 1, wh2, storeLoc;
    private long sku;
    private long junePeriodId;
    private long returnDocId;   // 역분개 대상

    @BeforeAll
    void login() {
        ResponseEntity<String> res = rest.postForEntity("/api/auth/login",
                jsonEntity("""
                        {"username":"admin","password":"admin1234!"}"""), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        cookie = res.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie).isNotNull();
    }

    // ── helpers ───────────────────────────────────────

    private HttpEntity<String> jsonEntity(String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            h.set(HttpHeaders.COOKIE, cookie);
        }
        return new HttpEntity<>(body, h);
    }

    private JsonNode get(String path) {
        ResponseEntity<String> res = rest.exchange(path, HttpMethod.GET, jsonEntity(null), String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).as("GET %s -> %s", path, res.getBody()).isTrue();
        return read(res.getBody());
    }

    private JsonNode post(String path, String body) {
        ResponseEntity<String> res = postRaw(path, body);
        assertThat(res.getStatusCode().is2xxSuccessful()).as("POST %s -> %s", path, res.getBody()).isTrue();
        return read(res.getBody());
    }

    private ResponseEntity<String> postRaw(String path, String body) {
        return rest.exchange(path, HttpMethod.POST, jsonEntity(body), String.class);
    }

    private JsonNode read(String body) {
        try {
            return body == null || body.isBlank() ? om.nullNode() : om.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(body, e);
        }
    }

    private long postDoc(String body) {
        long id = post("/api/docs", body).get("id").asLong();
        JsonNode posted = post("/api/docs/" + id + "/post", null);
        assertThat(posted.get("status").asText()).isEqualTo("POSTED");
        return id;
    }

    private long stockQty(long locationId) {
        return jdbc.sql("select coalesce(sum(qty),0) from stock_balance where location_id = :loc and sku_id = :sku")
                .param("loc", locationId).param("sku", sku).query(Long.class).single();
    }

    private JsonNode latestStatement(long counterpartyId, String ym) {
        for (JsonNode st : get("/api/settlement/statements?counterpartyId=" + counterpartyId + "&yearMonth=" + ym)) {
            if (st.get("latest").asBoolean()) {
                return st;
            }
        }
        throw new AssertionError("정산서 없음: cp=" + counterpartyId + " ym=" + ym);
    }

    // ── T1/T6: 마스터 + 한 달치 운영 (전기 매트릭스 전 행) ──

    @Test
    @Order(1)
    void masters() {
        label = post("/api/parties", """
                {"kind":"LABEL","name":"우주레코드"}""").get("id").asLong();
        hottracks = post("/api/parties", """
                {"kind":"RETAILER","name":"핫트랙스","settlementBasis":"SELL_IN","defaultSupplyRate":0.65}""")
                .get("id").asLong();
        JsonNode town = post("/api/parties", """
                {"kind":"RETAILER","name":"동네음반점","settlementBasis":"SELL_THROUGH","defaultSupplyRate":0.60}""");
        townRecords = town.get("id").asLong();
        assertThat(town.get("name").asText()).isEqualTo("동네음반점");   // UTF-8 왕복

        // SELL_THROUGH 거래처 → 매장 위치 자동 생성
        for (JsonNode loc : get("/api/locations")) {
            if (loc.get("kind").asText().equals("RETAILER")
                    && loc.get("retailerPartyId").asLong() == townRecords) {
                storeLoc = loc.get("id").asLong();
            }
        }
        assertThat(storeLoc).isPositive();
        wh2 = post("/api/locations", """
                {"name":"제2창고"}""").get("id").asLong();

        long album = post("/api/albums", """
                {"labelPartyId":%d,"title":"별빛","artistName":"루나틱스","releaseDate":"2026-06-01"}"""
                .formatted(label)).get("id").asLong();
        long ver = post("/api/albums/" + album + "/versions", """
                {"name":"A ver."}""").get("id").asLong();
        sku = post("/api/versions/" + ver + "/skus", """
                {"barcode":"8801234567890","name":"별빛 A ver.","listPrice":20000}""").get("id").asLong();
    }

    @Test
    @Order(2)
    void juneFlow() {
        postDoc("""
                {"docType":"OPENING","locationToId":%d,"occurredOn":"2026-06-01",
                 "lines":[{"skuId":%d,"qty":10}]}""".formatted(wh1, sku));
        postDoc("""
                {"docType":"PURCHASE_IN","counterpartyId":%d,"locationToId":%d,"occurredOn":"2026-06-01",
                 "lines":[{"skuId":%d,"qty":100,"unitPrice":11000}]}""".formatted(label, wh1, sku));
        postDoc("""
                {"docType":"SALE_OUT","counterpartyId":%d,"locationFromId":%d,"occurredOn":"2026-06-02",
                 "lines":[{"skuId":%d,"qty":30,"unitPrice":13000}]}""".formatted(hottracks, wh1, sku));
        postDoc("""
                {"docType":"CONSIGN_PLACE","counterpartyId":%d,"locationFromId":%d,"locationToId":%d,
                 "occurredOn":"2026-06-03","lines":[{"skuId":%d,"qty":20}]}"""
                .formatted(townRecords, wh1, storeLoc, sku));
        postDoc("""
                {"docType":"SALES_REPORT","counterpartyId":%d,"locationFromId":%d,"occurredOn":"2026-06-04",
                 "lines":[{"skuId":%d,"qty":5,"unitPrice":12000}]}""".formatted(townRecords, storeLoc, sku));
        returnDocId = postDoc("""
                {"docType":"CUSTOMER_RETURN","counterpartyId":%d,"locationToId":%d,"occurredOn":"2026-06-05",
                 "lines":[{"skuId":%d,"qty":3,"unitPrice":13000}]}""".formatted(hottracks, wh1, sku));
        postDoc("""
                {"docType":"TRANSFER","locationFromId":%d,"locationToId":%d,"occurredOn":"2026-06-06",
                 "lines":[{"skuId":%d,"qty":10}]}""".formatted(wh1, wh2, sku));
        postDoc("""
                {"docType":"ADJUST","locationToId":%d,"occurredOn":"2026-06-06",
                 "lines":[{"skuId":%d,"qty":-2,"note":"실사 차이"}]}""".formatted(wh2, sku));
        postDoc("""
                {"docType":"CONSIGN_RECALL","counterpartyId":%d,"locationFromId":%d,"locationToId":%d,
                 "occurredOn":"2026-06-07","lines":[{"skuId":%d,"qty":5}]}"""
                .formatted(townRecords, storeLoc, wh1, sku));
        postDoc("""
                {"docType":"PURCHASE_RETURN","counterpartyId":%d,"locationFromId":%d,"occurredOn":"2026-06-08",
                 "lines":[{"skuId":%d,"qty":10,"unitPrice":11000}]}""".formatted(label, wh1, sku));
        post("/api/settlement/payments", """
                {"counterpartyId":%d,"direction":"IN","amount":100000,"occurredOn":"2026-06-10"}"""
                .formatted(hottracks));
        post("/api/settlement/payments", """
                {"counterpartyId":%d,"direction":"OUT","amount":500000,"occurredOn":"2026-06-10"}"""
                .formatted(label));

        // 재고: 본사 48, 제2창고 8, 매장 10
        assertThat(stockQty(wh1)).isEqualTo(48);
        assertThat(stockQty(wh2)).isEqualTo(8);
        assertThat(stockQty(storeLoc)).isEqualTo(10);

        // 초동 = 30 + 5 − 3 = 32 (발매 7일 내 순출하)
        JsonNode fw = get("/api/reports/first-week");
        assertThat(fw.get(0).get("units").asLong()).isEqualTo(32);
    }

    // ── T4: 마감 → 정산서 숫자 전액 대조 ──────────────

    @Test
    @Order(3)
    void closeJune() {
        JsonNode close = post("/api/settlement/close", """
                {"yearMonth":"2026-06"}""");
        junePeriodId = close.get("periodId").asLong();
        assertThat(close.get("statements").asInt()).isEqualTo(3);

        // 핫트랙스(SELL_IN): 30×13,000 − 3×13,000 = 공급가 351,000 + VAT 35,100 − 입금 100,000
        JsonNode hot = latestStatement(hottracks, "2026-06");
        assertThat(hot.get("chargeSupply").asLong()).isEqualTo(351_000);
        assertThat(hot.get("chargeVat").asLong()).isEqualTo(35_100);
        assertThat(hot.get("chargeTotal").asLong()).isEqualTo(386_100);
        assertThat(hot.get("paymentTotal").asLong()).isEqualTo(-100_000);
        assertThat(hot.get("closingBalance").asLong()).isEqualTo(286_100);

        // 동네음반점(SELL_THROUGH): 판매보고 5×12,000만 정산 (진열·회수는 정산 아님)
        JsonNode town = latestStatement(townRecords, "2026-06");
        assertThat(town.get("chargeTotal").asLong()).isEqualTo(66_000);
        assertThat(town.get("closingBalance").asLong()).isEqualTo(66_000);

        // 기획사 매입: −100×11,000 + 10×11,000 = 공급가 −990,000, 지급 +500,000
        JsonNode lbl = latestStatement(label, "2026-06");
        assertThat(lbl.get("kind").asText()).isEqualTo("LABEL_PURCHASE");
        assertThat(lbl.get("chargeSupply").asLong()).isEqualTo(-990_000);
        assertThat(lbl.get("chargeTotal").asLong()).isEqualTo(-1_089_000);
        assertThat(lbl.get("paymentTotal").asLong()).isEqualTo(500_000);
        assertThat(lbl.get("closingBalance").asLong()).isEqualTo(-589_000);
    }

    // ── T2: 경계 거부 ─────────────────────────────────

    @Test
    @Order(4)
    void rejectsDoublePostAndShortStock() {
        // 이미 POSTED 문서 재확정 거부
        ResponseEntity<String> res = postRaw("/api/docs/" + returnDocId + "/post", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 잔량 부족 출고 거부
        long docId = post("/api/docs", """
                {"docType":"SALE_OUT","counterpartyId":%d,"locationFromId":%d,"occurredOn":"2026-07-01",
                 "lines":[{"skuId":%d,"qty":1000,"unitPrice":13000}]}""".formatted(hottracks, wh1, sku))
                .get("id").asLong();
        ResponseEntity<String> shortRes = postRaw("/api/docs/" + docId + "/post", null);
        assertThat(shortRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(shortRes.getBody()).contains("재고 부족");
    }

    // ── T3: 역분개 ────────────────────────────────────

    @Test
    @Order(5)
    void reverseRestoresEverything() {
        post("/api/docs/" + returnDocId + "/reverse", "{}");
        assertThat(stockQty(wh1)).isEqualTo(45);   // 반품 3개 취소 → 재고 −3

        // 원본+역분개 합계 0
        long netQty = jdbc.sql("""
                select coalesce(sum(ie.qty_delta),0) from inventory_entry ie
                join stock_doc_line l on l.id = ie.doc_line_id
                where l.doc_id = :doc or l.doc_id = (select id from stock_doc where reversal_of_doc_id = :doc)
                """).param("doc", returnDocId).query(Long.class).single();
        assertThat(netQty).isZero();

        // 역분개 정산분(+42,900)은 미배정 → 잔액에는 반영, 7월 정산서는 불변
        JsonNode balances = get("/api/reports/balances");
        for (JsonNode b : balances) {
            if (b.get("counterpartyId").asLong() == hottracks) {
                assertThat(b.get("balance").asLong()).isEqualTo(329_000);
                assertThat(b.get("unstamped").asLong()).isEqualTo(42_900);
            }
        }
    }

    // ── T5: 마감 후 반품 — 소급 정정(v2 재발행) ───────

    @Test
    @Order(6)
    void restateReturnRegeneratesStatement() {
        postDoc("""
                {"docType":"CUSTOMER_RETURN","counterpartyId":%d,"locationToId":%d,"occurredOn":"2026-07-02",
                 "restatePeriodId":%d,"lines":[{"skuId":%d,"qty":2,"unitPrice":13000}]}"""
                .formatted(hottracks, wh1, junePeriodId, sku));

        JsonNode v2 = latestStatement(hottracks, "2026-06");
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("chargeTotal").asLong()).isEqualTo(357_500);   // 386,100 − 28,600
        assertThat(v2.get("closingBalance").asLong()).isEqualTo(257_500);

        // v1 원본 보존
        JsonNode all = get("/api/settlement/statements?counterpartyId=" + hottracks + "&yearMonth=2026-06");
        assertThat(all).hasSize(2);
    }

    // ── T5: 마감 후 반품 — 차기 이월 ──────────────────

    @Test
    @Order(7)
    void carryForwardIntoJuly() {
        JsonNode close = post("/api/settlement/close", """
                {"yearMonth":"2026-07"}""");
        assertThat(close.get("statements").asInt()).isGreaterThanOrEqualTo(1);

        // 8월 정산서: 이월 257,500 + 역분개분 42,900 = 300,400
        JsonNode july = latestStatement(hottracks, "2026-07");
        assertThat(july.get("openingBalance").asLong()).isEqualTo(257_500);
        assertThat(july.get("chargeTotal").asLong()).isEqualTo(42_900);
        assertThat(july.get("closingBalance").asLong()).isEqualTo(300_400);

        // 8월이 마감된 뒤에는 7월로 소급 불가 (직전 마감 기간 한정)
        ResponseEntity<String> res = postRaw("/api/docs", """
                {"docType":"CUSTOMER_RETURN","counterpartyId":%d,"locationToId":%d,"occurredOn":"2026-07-20",
                 "restatePeriodId":%d,"lines":[{"skuId":%d,"qty":1,"unitPrice":13000}]}"""
                .formatted(hottracks, wh1, junePeriodId, sku));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("직전 마감");
    }

    // ── T1: 부가세 절사 (음수 포함) ───────────────────

    @Test
    @Order(8)
    void vatTruncation() {
        long saleDoc = postDoc("""
                {"docType":"SALE_OUT","counterpartyId":%d,"locationFromId":%d,"occurredOn":"2026-07-21",
                 "lines":[{"skuId":%d,"qty":3,"unitPrice":333}]}""".formatted(hottracks, wh1, sku));
        long cancelDoc = postDoc("""
                {"docType":"SALES_REPORT","counterpartyId":%d,"locationFromId":%d,"occurredOn":"2026-07-21",
                 "lines":[{"skuId":%d,"qty":-3,"unitPrice":333}]}""".formatted(townRecords, storeLoc, sku));

        record Row(long supply, long vat) {}
        Row sale = jdbc.sql("""
                select se.supply_amount, se.vat_amount from settlement_entry se
                join stock_doc_line l on l.id = se.doc_line_id where l.doc_id = :doc
                """).param("doc", saleDoc)
                .query((rs, i) -> new Row(rs.getLong(1), rs.getLong(2))).single();
        assertThat(sale.supply()).isEqualTo(999);
        assertThat(sale.vat()).isEqualTo(99);    // 99.9 → 절사

        Row cancel = jdbc.sql("""
                select se.supply_amount, se.vat_amount from settlement_entry se
                join stock_doc_line l on l.id = se.doc_line_id where l.doc_id = :doc
                """).param("doc", cancelDoc)
                .query((rs, i) -> new Row(rs.getLong(1), rs.getLong(2))).single();
        assertThat(cancel.supply()).isEqualTo(-999);
        assertThat(cancel.vat()).isEqualTo(-99); // 절대값 절사 후 부호
    }

    // ── T7: 원장 합계 == 잔량 캐시 불변식 ─────────────

    @Test
    @Order(9)
    void ledgerMatchesBalanceCache() {
        List<String> mismatches = jdbc.sql("""
                select coalesce(x.sku_id, b.sku_id) || '@' || coalesce(x.location_id, b.location_id) as key
                from (select sku_id, location_id, coalesce(owner_party_id, 0) as owner, sum(qty_delta) as total
                      from inventory_entry group by 1, 2, 3) x
                full outer join stock_balance b
                  on b.sku_id = x.sku_id and b.location_id = x.location_id
                 and coalesce(b.owner_party_id, 0) = x.owner
                where coalesce(x.total, 0) <> coalesce(b.qty, 0)
                """).query(String.class).list();
        assertThat(mismatches).isEmpty();

        long negative = jdbc.sql("select count(*) from stock_balance where qty < 0").query(Long.class).single();
        assertThat(negative).isZero();
    }

    // ── T8: 원장 append-only 트리거 ───────────────────

    @Test
    @Order(10)
    void ledgerIsAppendOnly() {
        assertThatThrownBy(() -> jdbc.sql("update inventory_entry set qty_delta = 999 where id = 1").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from inventory_entry where id = 1").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("update settlement_entry set amount = 0 where id = 1").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from settlement_entry where id = 1").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }
}
