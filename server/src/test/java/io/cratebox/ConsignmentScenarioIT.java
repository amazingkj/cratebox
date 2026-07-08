package io.cratebox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase ② 위탁 골든 시나리오 (DATA-MODEL.md §2 ② 행 + MG).
 * 별도 org에서 실행해 GoldenScenarioIT(①)와 마감 기간이 겹치지 않는다.
 *
 * 손계산 기준: SKU 정가 20,000, 위탁 수수료율 15%, 공급단가 14,000.
 *  - 위탁 판매 40 → 거래처 +560,000/+56,000, 기획사 −476,000/−47,600 (= 560,000×0.85)
 *  - 위탁 반품 5 → 거래처 −70,000/−7,000, 기획사 +59,500/+5,950
 *  - 판매보고 12 → 거래처 +168,000/+16,800, 기획사 −142,800/−14,280
 *  - 사입 10 @9,000 → 기획사 −90,000/−9,000
 *  기획사 잔액 = −714,230. 위탁분(−615,230)만 MG 회수 대상.
 *  MG 700,000 → 마감 시 회수 615,230, 잔여 84,770, 마감 후 기획사 잔액 = −99,000 (사입분).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestFlywayConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsignmentScenarioIT {

    @Autowired TestRestTemplate rest;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper om;
    @Autowired PasswordEncoder encoder;

    private String cookie;
    private long orgId;
    private long label, label2, kyobo, musicTown;
    private long warehouse, storeLoc;
    private long album, ver, sku;       // 위탁 계약 앨범
    private long album2, sku2;          // 계약 없는 앨범 (거부 검증용)
    private long advanceId;
    private long julySaleDocId;

    @BeforeAll
    void setupOrgAndLogin() {
        orgId = jdbc.sql("insert into org (name) values ('테스트유통2') returning id")
                .query(Long.class).single();
        jdbc.sql("""
                insert into app_user (org_id, username, password_hash, display_name)
                values (:org, 'consign_admin', :hash, '위탁관리자')
                """)
                .param("org", orgId).param("hash", encoder.encode("testpass1!"))
                .update();
        ResponseEntity<String> res = rest.postForEntity("/api/auth/login",
                jsonEntity("""
                        {"username":"consign_admin","password":"testpass1!"}"""), String.class);
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

    private void put(String path, String body) {
        ResponseEntity<String> res = rest.exchange(path, HttpMethod.PUT, jsonEntity(body), String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).as("PUT %s -> %s", path, res.getBody()).isTrue();
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

    /** 문서 생성 자체가 검증 오류로 거부되는지 */
    private void expectCreateError(String body, String messagePart) {
        ResponseEntity<String> res = postRaw("/api/docs", body);
        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(400);
        assertThat(res.getBody()).contains(messagePart);
    }

    private long poolQty(long locationId, Long ownerPartyId) {
        return jdbc.sql("""
                select coalesce(sum(qty),0) from stock_balance
                where org_id = :org and location_id = :loc and sku_id = :sku
                  and coalesce(owner_party_id, 0) = coalesce(:owner, 0)
                """)
                .param("org", orgId).param("loc", locationId).param("sku", sku).param("owner", ownerPartyId)
                .query(Long.class).single();
    }

    private long balance(long counterpartyId) {
        for (JsonNode b : get("/api/reports/balances")) {
            if (b.get("counterpartyId").asLong() == counterpartyId) {
                return b.get("balance").asLong();
            }
        }
        return 0;
    }

    /** 문서의 특정 상대방 정산 엔트리 합계 [supply, vat, amount] */
    private long[] docSettlement(long docId, long counterpartyId) {
        return jdbc.sql("""
                select coalesce(sum(se.supply_amount),0), coalesce(sum(se.vat_amount),0),
                       coalesce(sum(se.amount),0)
                from settlement_entry se
                join stock_doc_line l on l.id = se.doc_line_id
                where l.doc_id = :doc and se.counterparty_id = :cp
                """)
                .param("doc", docId).param("cp", counterpartyId)
                .query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)})
                .single();
    }

    // ── 마스터: 거래처·앨범·위탁 계약 ─────────────────

    @Test
    @Order(1)
    void masters() {
        label = post("/api/parties", """
                {"kind":"LABEL","name":"달빛레이블"}""").get("id").asLong();
        label2 = post("/api/parties", """
                {"kind":"LABEL","name":"별빛기획"}""").get("id").asLong();
        kyobo = post("/api/parties", """
                {"kind":"RETAILER","name":"교보문고","settlementBasis":"SELL_IN","defaultSupplyRate":0.70}""")
                .get("id").asLong();
        musicTown = post("/api/parties", """
                {"kind":"RETAILER","name":"뮤직타운","settlementBasis":"SELL_THROUGH","defaultSupplyRate":0.60}""")
                .get("id").asLong();
        warehouse = post("/api/locations", """
                {"name":"위탁창고"}""").get("id").asLong();
        for (JsonNode loc : get("/api/locations")) {
            if (loc.get("kind").asText().equals("RETAILER")
                    && loc.get("retailerPartyId").asLong() == musicTown) {
                storeLoc = loc.get("id").asLong();
            }
        }
        assertThat(storeLoc).isPositive();

        album = post("/api/albums", """
                {"labelPartyId":%d,"title":"달의 궤도","artistName":"문워커즈","releaseDate":"2026-06-05"}"""
                .formatted(label)).get("id").asLong();
        ver = post("/api/albums/" + album + "/versions", """
                {"name":"기본반"}""").get("id").asLong();
        sku = post("/api/versions/" + ver + "/skus", """
                {"barcode":"8809999000012","name":"달의 궤도 기본반","listPrice":20000}""").get("id").asLong();
        put("/api/albums/" + album + "/agreement", """
                {"kind":"CONSIGNMENT","commissionRate":0.15}""");

        // 위탁 계약이 없는 앨범 (거부 검증용)
        album2 = post("/api/albums", """
                {"labelPartyId":%d,"title":"무계약","artistName":"문워커즈"}""".formatted(label))
                .get("id").asLong();
        long ver2 = post("/api/albums/" + album2 + "/versions", """
                {"name":"기본반"}""").get("id").asLong();
        sku2 = post("/api/versions/" + ver2 + "/skus", """
                {"barcode":"8809999000029","name":"무계약 기본반","listPrice":15000}""").get("id").asLong();

        JsonNode agreements = get("/api/agreements");
        assertThat(agreements).hasSize(1);
        assertThat(agreements.get(0).get("albumId").asLong()).isEqualTo(album);
        assertThat(agreements.get(0).get("kind").asText()).isEqualTo("CONSIGNMENT");
    }

    // ── 수탁입고 + 소유 풀 분리 ───────────────────────

    @Test
    @Order(2)
    void consignInSeparatesOwnershipPools() {
        postDoc("""
                {"docType":"OPENING","locationToId":%d,"occurredOn":"2026-06-01",
                 "lines":[{"skuId":%d,"qty":10}]}""".formatted(warehouse, sku));
        postDoc("""
                {"docType":"CONSIGN_IN","counterpartyId":%d,"locationToId":%d,"occurredOn":"2026-06-03",
                 "lines":[{"skuId":%d,"qty":100}]}""".formatted(label, warehouse, sku));

        assertThat(poolQty(warehouse, null)).isEqualTo(10);
        assertThat(poolQty(warehouse, label)).isEqualTo(100);

        // 재고 리포트에 소유 구분이 노출된다
        boolean sawOwned = false, sawConsigned = false;
        for (JsonNode r : get("/api/reports/stock")) {
            if (r.get("skuId").asLong() != sku) {
                continue;
            }
            if (r.get("ownerPartyId").isNull()) {
                sawOwned = r.get("qty").asLong() == 10;
            } else if (r.get("ownerPartyId").asLong() == label) {
                sawConsigned = r.get("qty").asLong() == 100
                        && r.get("ownerName").asText().equals("달빛레이블");
            }
        }
        assertThat(sawOwned).as("자사 풀 10").isTrue();
        assertThat(sawConsigned).as("위탁 풀 100").isTrue();

        // 수탁입고는 정산 엔트리를 만들지 않는다
        assertThat(balance(label)).isZero();
    }

    // ── 6월 위탁 운영: 이중 전기 손계산 대조 ──────────

    @Test
    @Order(3)
    void juneConsignedFlow() {
        // 위탁 판매출고 40 @14,000
        long saleDoc = postDoc("""
                {"docType":"SALE_OUT","counterpartyId":%d,"locationFromId":%d,"occurredOn":"2026-06-05",
                 "lines":[{"skuId":%d,"qty":40,"unitPrice":14000,"ownerPartyId":%d}]}"""
                .formatted(kyobo, warehouse, sku, label));
        assertThat(docSettlement(saleDoc, kyobo)).containsExactly(560_000, 56_000, 616_000);
        assertThat(docSettlement(saleDoc, label)).containsExactly(-476_000, -47_600, -523_600);
        // 기획사 엔트리에는 단가를 기록하지 않는다 (수수료 차감 후 금액)
        Long pricedLabelEntries = jdbc.sql("""
                select count(*) from settlement_entry se
                join stock_doc_line l on l.id = se.doc_line_id
                where l.doc_id = :doc and se.counterparty_id = :cp and se.unit_price is not null
                """)
                .param("doc", saleDoc).param("cp", label)
                .query(Long.class).single();
        assertThat(pricedLabelEntries).isZero();

        // 위탁 거래처반품 5 @14,000
        long returnDoc = postDoc("""
                {"docType":"CUSTOMER_RETURN","counterpartyId":%d,"locationToId":%d,"occurredOn":"2026-06-08",
                 "lines":[{"skuId":%d,"qty":5,"unitPrice":14000,"ownerPartyId":%d}]}"""
                .formatted(kyobo, warehouse, sku, label));
        assertThat(docSettlement(returnDoc, kyobo)).containsExactly(-70_000, -7_000, -77_000);
        assertThat(docSettlement(returnDoc, label)).containsExactly(59_500, 5_950, 65_450);

        // 위탁 재고 진열 30 → 판매보고 12 @14,000
        postDoc("""
                {"docType":"CONSIGN_PLACE","counterpartyId":%d,"locationFromId":%d,"locationToId":%d,
                 "occurredOn":"2026-06-09","lines":[{"skuId":%d,"qty":30,"ownerPartyId":%d}]}"""
                .formatted(musicTown, warehouse, storeLoc, sku, label));
        long reportDoc = postDoc("""
                {"docType":"SALES_REPORT","counterpartyId":%d,"locationFromId":%d,"occurredOn":"2026-06-10",
                 "lines":[{"skuId":%d,"qty":12,"unitPrice":14000,"ownerPartyId":%d}]}"""
                .formatted(musicTown, storeLoc, sku, label));
        assertThat(docSettlement(reportDoc, musicTown)).containsExactly(168_000, 16_800, 184_800);
        assertThat(docSettlement(reportDoc, label)).containsExactly(-142_800, -14_280, -157_080);

        // 위탁 반납 20
        postDoc("""
                {"docType":"RETURN_TO_OWNER","counterpartyId":%d,"locationFromId":%d,"occurredOn":"2026-06-20",
                 "lines":[{"skuId":%d,"qty":20}]}""".formatted(label, warehouse, sku));

        // 같은 기획사에서 자사 사입 10 @9,000 (위탁과 무관한 매입 채무)
        postDoc("""
                {"docType":"PURCHASE_IN","counterpartyId":%d,"locationToId":%d,"occurredOn":"2026-06-25",
                 "lines":[{"skuId":%d,"qty":10,"unitPrice":9000}]}""".formatted(label, warehouse, sku));

        // 재고: 자사 10+10=20 / 위탁 100−40+5−30−20=15 / 매장 위탁 30−12=18
        assertThat(poolQty(warehouse, null)).isEqualTo(20);
        assertThat(poolQty(warehouse, label)).isEqualTo(15);
        assertThat(poolQty(storeLoc, label)).isEqualTo(18);

        // 잔액: 기획사 −523,600+65,450−157,080−99,000 = −714,230
        assertThat(balance(label)).isEqualTo(-714_230);
        assertThat(balance(kyobo)).isEqualTo(539_000);
        assertThat(balance(musicTown)).isEqualTo(184_800);

        // 초동(6/05~6/11): 판매 40 + 판매보고 12 − 반품 5 = 47 (위탁분도 집계)
        for (JsonNode r : get("/api/reports/first-week")) {
            if (r.get("albumVersionId").asLong() == ver) {
                assertThat(r.get("units").asLong()).isEqualTo(47);
            }
        }
    }

    // ── 위탁 규칙 위반 거부 ───────────────────────────

    @Test
    @Order(4)
    void rejectsInvalidConsignment() {
        // 위탁 풀 부족(15)이면 자사 풀(20)이 있어도 거부
        long id = post("/api/docs", """
                {"docType":"SALE_OUT","counterpartyId":%d,"locationFromId":%d,"occurredOn":"2026-06-26",
                 "lines":[{"skuId":%d,"qty":16,"unitPrice":14000,"ownerPartyId":%d}]}"""
                .formatted(kyobo, warehouse, sku, label)).get("id").asLong();
        ResponseEntity<String> res = postRaw("/api/docs/" + id + "/post", null);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).contains("재고 부족").contains("위탁");

        // 사입입고 라인에는 위탁 소유를 지정할 수 없다
        expectCreateError("""
                {"docType":"PURCHASE_IN","counterpartyId":%d,"locationToId":%d,"occurredOn":"2026-06-26",
                 "lines":[{"skuId":%d,"qty":5,"unitPrice":9000,"ownerPartyId":%d}]}"""
                .formatted(label, warehouse, sku, label), "위탁 재고를 지정할 수 없습니다");

        // 위탁 계약이 없는 앨범은 수탁입고 불가
        expectCreateError("""
                {"docType":"CONSIGN_IN","counterpartyId":%d,"locationToId":%d,"occurredOn":"2026-06-26",
                 "lines":[{"skuId":%d,"qty":10}]}""".formatted(label, warehouse, sku2),
                "위탁 계약이 등록되지 않은 앨범");

        // 다른 기획사의 앨범은 수탁입고 불가
        expectCreateError("""
                {"docType":"CONSIGN_IN","counterpartyId":%d,"locationToId":%d,"occurredOn":"2026-06-26",
                 "lines":[{"skuId":%d,"qty":10}]}""".formatted(label2, warehouse, sku),
                "상대 기획사의 앨범이 아닙니다");

        // 소유자가 앨범 기획사가 아니면 거부
        expectCreateError("""
                {"docType":"SALE_OUT","counterpartyId":%d,"locationFromId":%d,"occurredOn":"2026-06-26",
                 "lines":[{"skuId":%d,"qty":1,"unitPrice":14000,"ownerPartyId":%d}]}"""
                .formatted(kyobo, warehouse, sku, label2), "앨범의 기획사여야 합니다");
    }

    // ── MG 선급금 + 마감: 앨범 단위 회수 ──────────────

    @Test
    @Order(5)
    void advanceAndClose() {
        // 위탁 계약 없는 앨범에는 MG 불가
        ResponseEntity<String> bad = postRaw("/api/advances", """
                {"labelPartyId":%d,"albumId":%d,"amount":100000,"paidOn":"2026-06-15"}"""
                .formatted(label, album2));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);

        advanceId = post("/api/advances", """
                {"labelPartyId":%d,"albumId":%d,"amount":700000,"paidOn":"2026-06-15","memo":"계약금"}"""
                .formatted(label, album)).get("id").asLong();

        JsonNode close = post("/api/settlement/close", """
                {"yearMonth":"2026-06"}""");
        assertThat(close.get("statements").asInt()).isEqualTo(3);

        // 기획사 정산서: 위탁분(−615,230)만 MG에서 회수, 사입분(−99,000)은 지급 대상으로 남는다
        JsonNode st = latestStatement(label, "2026-06");
        assertThat(st.get("kind").asText()).isEqualTo("LABEL_CONSIGN");
        assertThat(st.get("openingBalance").asLong()).isZero();
        assertThat(st.get("chargeSupply").asLong()).isEqualTo(-649_300);
        assertThat(st.get("chargeVat").asLong()).isEqualTo(-64_930);
        assertThat(st.get("chargeTotal").asLong()).isEqualTo(-714_230);
        assertThat(st.get("advanceTotal").asLong()).isEqualTo(615_230);
        assertThat(st.get("closingBalance").asLong()).isEqualTo(-99_000);

        // 명세 라인에 MG 차감이 보인다
        JsonNode detail = get("/api/settlement/statements/" + st.get("id").asLong());
        boolean sawRecoupLine = false;
        for (JsonNode line : detail.get("lines")) {
            if (line.get("entryType").asText().equals("ADVANCE_RECOUP")) {
                sawRecoupLine = line.get("label").asText().equals("MG 차감: 달의 궤도")
                        && line.get("amount").asLong() == 615_230;
            }
        }
        assertThat(sawRecoupLine).isTrue();

        // 거래처 정산서는 위탁 여부와 무관하게 동일
        assertThat(latestStatement(kyobo, "2026-06").get("closingBalance").asLong()).isEqualTo(539_000);
        assertThat(latestStatement(musicTown, "2026-06").get("closingBalance").asLong()).isEqualTo(184_800);

        // 선급금 잔여 = 700,000 − 615,230 = 84,770
        JsonNode adv = get("/api/advances").get(0);
        assertThat(adv.get("recouped").asLong()).isEqualTo(615_230);
        assertThat(adv.get("remaining").asLong()).isEqualTo(84_770);

        // 회수가 시작된 선급금은 삭제 불가
        ResponseEntity<String> del = rest.exchange("/api/advances/" + advanceId, HttpMethod.DELETE,
                jsonEntity(null), String.class);
        assertThat(del.getStatusCode().value()).isEqualTo(400);
        assertThat(del.getBody()).contains("삭제할 수 없습니다");

        // 마감 후 기획사 잔액 = 사입분만 남는다
        assertThat(balance(label)).isEqualTo(-99_000);
    }

    private JsonNode latestStatement(long counterpartyId, String ym) {
        for (JsonNode st : get("/api/settlement/statements?counterpartyId=" + counterpartyId + "&yearMonth=" + ym)) {
            if (st.get("latest").asBoolean()) {
                return st;
            }
        }
        throw new AssertionError("정산서 없음: cp=" + counterpartyId + " ym=" + ym);
    }

    // ── 수수료 절사 + 역분개 ──────────────────────────

    @Test
    @Order(6)
    void commissionTruncationAndReversal() {
        // 333 × 0.85 = 283.05 → 283 (원 미만 절사), VAT 28
        julySaleDocId = postDoc("""
                {"docType":"SALE_OUT","counterpartyId":%d,"locationFromId":%d,"occurredOn":"2026-07-01",
                 "lines":[{"skuId":%d,"qty":1,"unitPrice":333,"ownerPartyId":%d}]}"""
                .formatted(kyobo, warehouse, sku, label));
        assertThat(docSettlement(julySaleDocId, kyobo)).containsExactly(333, 33, 366);
        assertThat(docSettlement(julySaleDocId, label)).containsExactly(-283, -28, -311);
        assertThat(balance(label)).isEqualTo(-99_311);

        // 역분개: 위탁 풀·기획사/거래처 엔트리 모두 원복
        post("/api/docs/" + julySaleDocId + "/reverse", """
                {"occurredOn":"2026-07-02"}""");
        assertThat(poolQty(warehouse, label)).isEqualTo(15);
        assertThat(balance(label)).isEqualTo(-99_000);
        assertThat(balance(kyobo)).isEqualTo(539_000);
    }

    // ── 불변식: Σ원장 == 잔량 캐시, 음수 재고 없음 ────

    @Test
    @Order(7)
    void ledgerMatchesBalanceCache() {
        Long mismatches = jdbc.sql("""
                select count(*) from (
                    select coalesce(e.sku_id, b.sku_id) as sku_id
                    from (select sku_id, location_id, coalesce(owner_party_id, 0) as owner,
                                 sum(qty_delta) as total
                          from inventory_entry where org_id = :org
                          group by sku_id, location_id, coalesce(owner_party_id, 0)) e
                    full outer join (select sku_id, location_id, coalesce(owner_party_id, 0) as owner, qty
                                     from stock_balance where org_id = :org) b
                        on b.sku_id = e.sku_id and b.location_id = e.location_id and b.owner = e.owner
                    where coalesce(e.total, 0) <> coalesce(b.qty, 0)
                ) x
                """)
                .param("org", orgId).query(Long.class).single();
        assertThat(mismatches).isZero();

        Long negatives = jdbc.sql("select count(*) from stock_balance where org_id = :org and qty < 0")
                .param("org", orgId).query(Long.class).single();
        assertThat(negatives).isZero();
    }

    // ── 기획사 포털: 자기 데이터만, 운영 API 차단 ─────

    @Test
    @Order(8)
    void portalScopedAccess() {
        // 운영자가 포털 계정 발급 (기획사당 1개)
        post("/api/parties/" + label + "/portal-user", """
                {"username":"moonlight_portal","password":"portal123!"}""");
        ResponseEntity<String> dup = postRaw("/api/parties/" + label + "/portal-user", """
                {"username":"another","password":"portal123!"}""");
        assertThat(dup.getStatusCode().value()).isEqualTo(400);

        // 기획사 로그인
        ResponseEntity<String> login = rest.exchange("/api/auth/login", HttpMethod.POST,
                entityWith(null, """
                        {"username":"moonlight_portal","password":"portal123!"}"""), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(login.getBody()).get("role").asText()).isEqualTo("LABEL");
        String portalCookie = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE);

        // 자기 소유(위탁) 재고: 창고 15 + 매장 18
        JsonNode stock = read(rest.exchange("/api/portal/stock", HttpMethod.GET,
                entityWith(portalCookie, null), String.class).getBody());
        long totalConsigned = 0;
        for (JsonNode r : stock) {
            totalConsigned += r.get("qty").asLong();
        }
        assertThat(totalConsigned).isEqualTo(33);

        // 요약: 잔액 = 마감 후 사입분 −99,000
        JsonNode summary = read(rest.exchange("/api/portal/summary", HttpMethod.GET,
                entityWith(portalCookie, null), String.class).getBody());
        assertThat(summary.get("labelName").asText()).isEqualTo("달빛레이블");
        assertThat(summary.get("balance").asLong()).isEqualTo(-99_000);

        // 자기 정산서만 보인다 (위탁 정산서 + 상세 접근 가능)
        JsonNode statements = read(rest.exchange("/api/portal/statements", HttpMethod.GET,
                entityWith(portalCookie, null), String.class).getBody());
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).get("kind").asText()).isEqualTo("LABEL_CONSIGN");
        assertThat(statements.get(0).get("closingBalance").asLong()).isEqualTo(-99_000);
        long stId = statements.get(0).get("id").asLong();
        assertThat(rest.exchange("/api/portal/statements/" + stId, HttpMethod.GET,
                entityWith(portalCookie, null), String.class).getStatusCode().value()).isEqualTo(200);

        // 남의 정산서(교보문고)는 404
        long kyoboStId = latestStatement(kyobo, "2026-06").get("id").asLong();
        assertThat(rest.exchange("/api/portal/statements/" + kyoboStId, HttpMethod.GET,
                entityWith(portalCookie, null), String.class).getStatusCode().value()).isEqualTo(404);

        // 기획사 계정은 운영 API 차단, 운영자 계정은 포털 차단
        assertThat(rest.exchange("/api/docs", HttpMethod.GET,
                entityWith(portalCookie, null), String.class).getStatusCode().value()).isEqualTo(403);
        assertThat(rest.exchange("/api/portal/stock", HttpMethod.GET,
                jsonEntity(null), String.class).getStatusCode().value()).isEqualTo(403);
    }

    private HttpEntity<String> entityWith(String ck, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (ck != null) {
            h.set(HttpHeaders.COOKIE, ck);
        }
        return new HttpEntity<>(body, h);
    }
}
