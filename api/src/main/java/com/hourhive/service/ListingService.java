package com.hourhive.service;

import com.hourhive.api.Dtos.CategoryCount;
import com.hourhive.api.Dtos.ListingRequest;
import com.hourhive.api.Dtos.ListingView;
import com.hourhive.error.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ListingService {

    private static final String SELECT = """
            select l.id, l.owner_id, u.display_name, l.category, l.title, l.description,
                   l.minutes, l.active, l.created_at,
                   coalesce(r.avg_rating, 0) as avg_rating, coalesce(r.cnt, 0) as cnt
            from listings l
            join users u on u.id = l.owner_id
            left join (
                select reviewee_id, cast(avg(rating) as double precision) as avg_rating, count(*) as cnt
                from reviews group by reviewee_id
            ) r on r.reviewee_id = l.owner_id
            """;

    private final JdbcClient jdbc;

    public ListingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ListingView create(long ownerId, ListingRequest req) {
        long id = jdbc.sql("""
                insert into listings (owner_id, category, title, description, minutes)
                values (:o, :c, :t, :d, :m) returning id
                """)
                .param("o", ownerId)
                .param("c", req.category().trim())
                .param("t", req.title().trim())
                .param("d", req.description().trim())
                .param("m", req.minutes())
                .query(Long.class)
                .single();
        return get(id);
    }

    public List<ListingView> search(String q, String category) {
        StringBuilder sql = new StringBuilder(SELECT).append(" where l.active = true");
        Map<String, Object> params = new HashMap<>();
        if (category != null && !category.isBlank()) {
            sql.append(" and l.category = :cat");
            params.put("cat", category.trim());
        }
        if (q != null && !q.isBlank()) {
            sql.append(" and (lower(l.title) like :q or lower(l.description) like :q)");
            params.put("q", "%" + q.trim().toLowerCase(Locale.ROOT) + "%");
        }
        sql.append(" order by l.created_at desc limit 60");
        return jdbc.sql(sql.toString()).params(params).query(ListingService::map).list();
    }

    public List<ListingView> mine(long ownerId) {
        return jdbc.sql(SELECT + " where l.owner_id = :o order by l.created_at desc")
                .param("o", ownerId)
                .query(ListingService::map)
                .list();
    }

    public ListingView get(long id) {
        return jdbc.sql(SELECT + " where l.id = :id")
                .param("id", id)
                .query(ListingService::map)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Listing not found"));
    }

    public void deactivate(long ownerId, long id) {
        ListingView existing = get(id);
        if (existing.ownerId() != ownerId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only remove your own listings");
        }
        jdbc.sql("update listings set active = false where id = :id").param("id", id).update();
    }

    public List<CategoryCount> categories() {
        return jdbc.sql("""
                select category, count(*) as n from listings
                where active = true group by category order by n desc, category
                """)
                .query((rs, i) -> new CategoryCount(rs.getString("category"), rs.getInt("n")))
                .list();
    }

    private static ListingView map(ResultSet rs, int rowNum) throws SQLException {
        double rating = Math.round(rs.getDouble("avg_rating") * 10.0) / 10.0;
        return new ListingView(
                rs.getLong("id"), rs.getLong("owner_id"), rs.getString("display_name"),
                rating, rs.getInt("cnt"),
                rs.getString("category"), rs.getString("title"), rs.getString("description"),
                rs.getInt("minutes"), rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant());
    }
}
