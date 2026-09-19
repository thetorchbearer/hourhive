package com.hourhive.service;

import com.hourhive.api.Dtos.LedgerView;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Every minute that moves in HourHive is an immutable ledger row. Balance = SUM(delta). */
@Service
public class LedgerService {

    private final JdbcClient jdbc;

    public LedgerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(long userId, int delta, String kind, Long bookingId, String note) {
        jdbc.sql("""
                insert into ledger_entries (user_id, delta_minutes, kind, booking_id, note)
                values (:u, :d, :k, :b, :n)
                """)
                .param("u", userId)
                .param("d", delta)
                .param("k", kind)
                .param("b", bookingId)
                .param("n", note)
                .update();
    }

    public int balance(long userId) {
        Long sum = jdbc.sql("select coalesce(sum(delta_minutes), 0) from ledger_entries where user_id = :u")
                .param("u", userId)
                .query(Long.class)
                .single();
        return sum.intValue();
    }

    public List<LedgerView> history(long userId) {
        return jdbc.sql("""
                select id, delta_minutes, kind, booking_id, note, created_at
                from ledger_entries where user_id = :u order by id desc limit 100
                """)
                .param("u", userId)
                .query((rs, n) -> new LedgerView(
                        rs.getLong("id"),
                        rs.getInt("delta_minutes"),
                        rs.getString("kind"),
                        rs.getObject("booking_id", Long.class),
                        rs.getString("note"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
