package io.cratebox.common;

import java.time.Instant;
import java.time.OffsetDateTime;

public final class JdbcUtils {
    private JdbcUtils() {}

    /** PG JDBC는 timestamptz→Instant 직접 변환을 지원하지 않는다 */
    public static Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.toInstant() : null;
    }
}
