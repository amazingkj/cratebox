package io.cratebox.inventory;

import io.cratebox.common.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StockDocDao {

    /** 목록 화면용 요약 행 */
    public record DocSummary(Long id, String docNo, String docType, String status,
                             Long counterpartyId, String counterpartyName,
                             String locationFromName, String locationToName,
                             LocalDate occurredOn, String memo, int lineCount, long totalQty,
                             Long reversalOfDocId, Long reversedByDocId) {}

    private static final RowMapper<StockDoc> DOC_MAPPER = (rs, i) -> mapDoc(rs);
    private static final RowMapper<StockDoc.StockDocLine> LINE_MAPPER = (rs, i) ->
            new StockDoc.StockDocLine(rs.getLong("id"), rs.getInt("line_no"), rs.getLong("sku_id"),
                    rs.getInt("qty"), (Long) rs.getObject("unit_price"),
                    (Long) rs.getObject("owner_party_id"), rs.getString("note"));

    private final JdbcClient jdbc;

    public StockDocDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static StockDoc mapDoc(ResultSet rs) throws SQLException {
        return new StockDoc(rs.getLong("id"), rs.getLong("org_id"), rs.getString("doc_no"),
                DocType.valueOf(rs.getString("doc_type")), rs.getString("status"),
                (Long) rs.getObject("counterparty_id"), (Long) rs.getObject("location_from_id"),
                (Long) rs.getObject("location_to_id"), rs.getObject("occurred_on", LocalDate.class),
                (Long) rs.getObject("restate_period_id"), rs.getString("memo"),
                rs.getLong("created_by"),
                io.cratebox.common.JdbcUtils.toInstant(rs.getObject("created_at", OffsetDateTime.class)),
                io.cratebox.common.JdbcUtils.toInstant(rs.getObject("posted_at", OffsetDateTime.class)),
                (Long) rs.getObject("reversal_of_doc_id"),
                (Long) rs.getObject("reversed_by_doc_id"), List.of());
    }

    public Long insert(StockDoc doc) {
        Long docId = jdbc.sql("""
                insert into stock_doc (org_id, doc_no, doc_type, status, counterparty_id,
                    location_from_id, location_to_id, occurred_on, restate_period_id, memo,
                    created_by, posted_at, reversal_of_doc_id)
                values (:org, :docNo, :type, :status, :cp, :from, :to, :on, :restate, :memo,
                    :by, :postedAt, :revOf)
                returning id
                """)
                .param("org", doc.orgId()).param("docNo", doc.docNo()).param("type", doc.docType().name())
                .param("status", doc.status()).param("cp", doc.counterpartyId())
                .param("from", doc.locationFromId()).param("to", doc.locationToId())
                .param("on", doc.occurredOn()).param("restate", doc.restatePeriodId())
                .param("memo", doc.memo()).param("by", doc.createdBy())
                .param("postedAt", doc.postedAt() != null ? java.sql.Timestamp.from(doc.postedAt()) : null)
                .param("revOf", doc.reversalOfDocId())
                .query(Long.class).single();
        insertLines(doc.orgId(), docId, doc.lines());
        return docId;
    }

    private void insertLines(Long orgId, Long docId, List<StockDoc.StockDocLine> lines) {
        for (StockDoc.StockDocLine l : lines) {
            jdbc.sql("""
                    insert into stock_doc_line (org_id, doc_id, line_no, sku_id, qty, unit_price,
                        owner_party_id, note)
                    values (:org, :doc, :no, :sku, :qty, :price, :owner, :note)
                    """)
                    .param("org", orgId).param("doc", docId).param("no", l.lineNo())
                    .param("sku", l.skuId()).param("qty", l.qty()).param("price", l.unitPrice())
                    .param("owner", l.ownerPartyId()).param("note", l.note())
                    .update();
        }
    }

    public void updateDraft(StockDoc doc) {
        jdbc.sql("""
                update stock_doc set counterparty_id = :cp, location_from_id = :from,
                    location_to_id = :to, occurred_on = :on, restate_period_id = :restate, memo = :memo
                where id = :id and org_id = :org and status = 'DRAFT'
                """)
                .param("cp", doc.counterpartyId()).param("from", doc.locationFromId())
                .param("to", doc.locationToId()).param("on", doc.occurredOn())
                .param("restate", doc.restatePeriodId()).param("memo", doc.memo())
                .param("id", doc.id()).param("org", doc.orgId())
                .update();
        jdbc.sql("delete from stock_doc_line where doc_id = :doc").param("doc", doc.id()).update();
        insertLines(doc.orgId(), doc.id(), doc.lines());
    }

    public StockDoc findById(Long orgId, Long id) {
        StockDoc doc = jdbc.sql("select * from stock_doc where id = :id and org_id = :org")
                .param("id", id).param("org", orgId)
                .query(DOC_MAPPER).optional()
                .orElseThrow(() -> new NotFoundException("문서가 없습니다: " + id));
        List<StockDoc.StockDocLine> lines = jdbc
                .sql("select * from stock_doc_line where doc_id = :doc order by line_no")
                .param("doc", id).query(LINE_MAPPER).list();
        return withLines(doc, lines);
    }

    private static StockDoc withLines(StockDoc d, List<StockDoc.StockDocLine> lines) {
        return new StockDoc(d.id(), d.orgId(), d.docNo(), d.docType(), d.status(), d.counterpartyId(),
                d.locationFromId(), d.locationToId(), d.occurredOn(), d.restatePeriodId(), d.memo(),
                d.createdBy(), d.createdAt(), d.postedAt(), d.reversalOfDocId(), d.reversedByDocId(), lines);
    }

    public List<DocSummary> list(Long orgId, String docType, String status, Long counterpartyId,
                                 LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select d.id, d.doc_no, d.doc_type, d.status, d.counterparty_id, p.name as cp_name,
                       lf.name as loc_from_name, lt.name as loc_to_name, d.occurred_on, d.memo,
                       d.reversal_of_doc_id, d.reversed_by_doc_id,
                       coalesce(s.line_count, 0) as line_count, coalesce(s.total_qty, 0) as total_qty
                from stock_doc d
                left join party p on p.id = d.counterparty_id
                left join location lf on lf.id = d.location_from_id
                left join location lt on lt.id = d.location_to_id
                left join lateral (
                    select count(*) as line_count, sum(l.qty) as total_qty
                    from stock_doc_line l where l.doc_id = d.id
                ) s on true
                where d.org_id = :org
                  and (:type::text is null or d.doc_type = :type)
                  and (:status::text is null or d.status = :status)
                  and (:cp::bigint is null or d.counterparty_id = :cp)
                  and (:from::date is null or d.occurred_on >= :from)
                  and (:to::date is null or d.occurred_on <= :to)
                order by d.id desc
                limit 500
                """)
                .param("org", orgId).param("type", docType).param("status", status)
                .param("cp", counterpartyId).param("from", from).param("to", to)
                .query((rs, i) -> new DocSummary(rs.getLong("id"), rs.getString("doc_no"),
                        rs.getString("doc_type"), rs.getString("status"),
                        (Long) rs.getObject("counterparty_id"), rs.getString("cp_name"),
                        rs.getString("loc_from_name"), rs.getString("loc_to_name"),
                        rs.getObject("occurred_on", LocalDate.class), rs.getString("memo"),
                        rs.getInt("line_count"), rs.getLong("total_qty"),
                        (Long) rs.getObject("reversal_of_doc_id"), (Long) rs.getObject("reversed_by_doc_id")))
                .list();
    }

    public void deleteDraft(Long orgId, Long id) {
        jdbc.sql("delete from stock_doc_line where doc_id = :id").param("id", id).update();
        int n = jdbc.sql("delete from stock_doc where id = :id and org_id = :org and status = 'DRAFT'")
                .param("id", id).param("org", orgId).update();
        if (n == 0) {
            throw new NotFoundException("삭제할 DRAFT 문서가 없습니다: " + id);
        }
    }

    public void markPosted(Long docId) {
        jdbc.sql("update stock_doc set status = 'POSTED', posted_at = now() where id = :id")
                .param("id", docId).update();
    }

    public void markReversed(Long docId, Long reversalDocId) {
        jdbc.sql("update stock_doc set status = 'REVERSED', reversed_by_doc_id = :rev where id = :id")
                .param("id", docId).param("rev", reversalDocId).update();
    }

    public Map<Long, String> skuNames(List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("select id, name from sku where id in (:ids)").param("ids", skuIds)
                .query((rs, i) -> Map.entry(rs.getLong("id"), rs.getString("name")))
                .list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
