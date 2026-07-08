package io.cratebox.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 최초 기동 시 시드: org + 관리자 계정 + 기본 창고 */
@Component
public class DataInitializer implements CommandLineRunner {

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final String adminPassword;

    public DataInitializer(JdbcClient jdbc, PasswordEncoder encoder,
                           @Value("${cratebox.admin-password}") String adminPassword) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Long orgCount = jdbc.sql("select count(*) from org").query(Long.class).single();
        if (orgCount > 0) {
            return;
        }
        Long orgId = jdbc.sql("insert into org (name) values ('cratebox') returning id")
                .query(Long.class).single();
        jdbc.sql("insert into app_user (org_id, username, password_hash, display_name) values (?, ?, ?, ?)")
                .params(orgId, "admin", encoder.encode(adminPassword), "관리자")
                .update();
        jdbc.sql("insert into location (org_id, kind, name) values (?, 'WAREHOUSE', '본사 창고')")
                .param(orgId)
                .update();
    }
}
