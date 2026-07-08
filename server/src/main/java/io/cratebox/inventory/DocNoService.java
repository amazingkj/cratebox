package io.cratebox.inventory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 문서번호 채번: 타입코드-YYMM-#### (org × 타입 × 월별 순번) */
@Service
public class DocNoService {

    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyMM");

    private final JdbcClient jdbc;

    public DocNoService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String next(Long orgId, DocType type, LocalDate occurredOn) {
        String prefix = type.prefix + "-" + occurredOn.format(YYMM);
        int no = jdbc.sql("""
                insert into doc_no_seq (org_id, prefix, last_no) values (:org, :prefix, 1)
                on conflict (org_id, prefix) do update set last_no = doc_no_seq.last_no + 1
                returning last_no
                """)
                .param("org", orgId).param("prefix", prefix)
                .query(Integer.class).single();
        return "%s-%04d".formatted(prefix, no);
    }
}
