package io.cratebox.settings;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class OrgProfiles {

    private final JdbcClient jdbc;

    public OrgProfiles(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public OrgProfile get(Long orgId) {
        return jdbc.sql("select name, biz_reg_no, ceo_name, address, phone, email from org where id = :id")
                .param("id", orgId)
                .query((rs, i) -> new OrgProfile(rs.getString("name"), rs.getString("biz_reg_no"),
                        rs.getString("ceo_name"), rs.getString("address"), rs.getString("phone"),
                        rs.getString("email")))
                .single();
    }

    public void update(Long orgId, OrgProfile p) {
        jdbc.sql("""
                update org set name = :name, biz_reg_no = :biz, ceo_name = :ceo,
                    address = :addr, phone = :phone, email = :email
                where id = :id
                """)
                .param("id", orgId).param("name", p.name()).param("biz", p.bizRegNo())
                .param("ceo", p.ceoName()).param("addr", p.address()).param("phone", p.phone())
                .param("email", p.email())
                .update();
    }
}
