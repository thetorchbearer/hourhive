package com.hourhive.service;

import com.hourhive.api.Dtos.CommunityStats;
import com.hourhive.api.Dtos.LeaderboardEntry;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class StatsService {

    private final JdbcClient jdbc;

    public StatsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Top helpers ranked by completed minutes given. */
    public List<LeaderboardEntry> leaderboard() {
        return jdbc.sql("""
                select u.id, u.display_name,
                       sum(b.minutes) as given, count(b.id) as sessions,
                       coalesce((select cast(avg(r.rating) as double precision)
                                 from reviews r where r.reviewee_id = u.id), 0) as rating
                from users u
                join bookings b on b.provider_id = u.id and b.status = 'COMPLETED'
                group by u.id, u.display_name
                order by given desc
                limit 10
                """)
                .query((rs, n) -> new LeaderboardEntry(
                        rs.getLong("id"),
                        rs.getString("display_name"),
                        (int) rs.getLong("given"),
                        (int) rs.getLong("sessions"),
                        Math.round(rs.getDouble("rating") * 10.0) / 10.0))
                .list();
    }

    public CommunityStats community() {
        return jdbc.sql("""
                select (select count(*) from users) as members,
                       (select count(*) from listings where active = true) as listings,
                       (select count(*) from bookings where status = 'COMPLETED') as sessions,
                       (select coalesce(sum(minutes), 0) from bookings where status = 'COMPLETED') as minutes
                """)
                .query((rs, n) -> new CommunityStats(
                        (int) rs.getLong("members"),
                        (int) rs.getLong("listings"),
                        (int) rs.getLong("sessions"),
                        (int) rs.getLong("minutes")))
                .single();
    }

    public boolean databaseUp() {
        try {
            jdbc.sql("select 1").query(Integer.class).single();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
