package com.hourhive.service;

import com.hourhive.api.Dtos.CategoryCount;
import com.hourhive.api.Dtos.ListingRequest;
import com.hourhive.api.Dtos.ListingView;
import com.hourhive.api.Dtos.PageResponse;
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

    /** Search filters. Any field may be null/blank to skip that filter. */
    public record SearchQuery(String q, String category, String sort, Integer minMinutes, Integer maxMinutes,
                              Double minRating, Integer availableDay, int page, int size) {
    }

    public SearchQuery normalize(SearchQuery sq) {
        int size = Math.min(Math.max(sq.size(), 1), 50);
        int page = Math.max(sq.page(), 0);
        return new SearchQuery(sq.q(), sq.category(), sq.sort(), sq.minMinutes(), sq.maxMinutes(),
                sq.minRating(), sq.availableDay(), page, size);
    }

    public PageResponse<ListingView> search(SearchQuery raw) {
        SearchQuery sq = normalize(raw);
        StringBuilder where = new StringBuilder(" where l.active = true and u.disabled = false");
        Map<String, Object> params = new HashMap<>();
        if (sq.category() != null && !sq.category().isBlank()) {
            where.append(" and l.category = :cat");
            params.put("cat", sq.category().trim());
        }
        if (sq.q() != null && !sq.q().isBlank()) {
            where.append(" and (lower(l.title) like :q or lower(l.description) like :q or lower(l.category) like :q)");
            params.put("q", "%" + sq.q().trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (sq.minMinutes() != null && sq.minMinutes() > 0) {
            where.append(" and l.minutes >= :minm");
            params.put("minm", sq.minMinutes());
        }
        if (sq.maxMinutes() != null && sq.maxMinutes() > 0) {
            where.append(" and l.minutes <= :maxm");
            params.put("maxm", sq.maxMinutes());
        }
        if (sq.minRating() != null && sq.minRating() > 0) {
            where.append(" and coalesce(r.avg_rating, 0) >= :minr");
            params.put("minr", sq.minRating());
        }
        if (sq.availableDay() != null && sq.availableDay() >= 1 && sq.availableDay() <= 7) {
            where.append(" and exists (select 1 from availability_slots s"
                    + " where s.user_id = l.owner_id and s.day_of_week = :day)");
            params.put("day", sq.availableDay());
        }
        String order = switch (sq.sort() == null ? "" : sq.sort()) {
            case "rating" -> " order by coalesce(r.avg_rating, 0) desc, r.cnt desc nulls last, l.created_at desc";
            case "shortest" -> " order by l.minutes asc, l.created_at desc";
            case "longest" -> " order by l.minutes desc, l.created_at desc";
            case "oldest" -> " order by l.created_at asc";
            default -> " order by l.created_at desc";
        };

        Long total = jdbc.sql("select count(*) from (" + SELECT + where + ") t")
                .params(params).query(Long.class).single();
        Map<String, Object> pageParams = new HashMap<>(params);
        pageParams.put("lim", sq.size());
        pageParams.put("off", sq.page() * sq.size());
        List<ListingView> items = jdbc.sql(SELECT + where + order + " limit :lim offset :off")
                .params(pageParams).query(ListingService::map).list();
        return PageResponse.of(items, sq.page(), sq.size(), total);
    }

    /** Active listings from other members, used by the helper-matching engine (unpaginated, capped). */
    public List<ListingView> candidates(long excludeOwnerId) {
        return jdbc.sql(SELECT + " where l.active = true and u.disabled = false and l.owner_id <> :o"
                + " order by l.created_at desc limit 300")
                .param("o", excludeOwnerId)
                .query(ListingService::map)
                .list();
    }

    public List<ListingView> activeByOwner(long ownerId) {
        return jdbc.sql(SELECT + " where l.owner_id = :o and l.active = true order by l.created_at desc")
                .param("o", ownerId)
                .query(ListingService::map)
                .list();
    }

    public ListingView update(long ownerId, long id, ListingRequest req) {
        ListingView existing = get(id);
        if (existing.ownerId() != ownerId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only edit your own listings");
        }
        jdbc.sql("""
                update listings set category = :c, title = :t, description = :d, minutes = :m
                where id = :id
                """)
                .param("c", req.category().trim())
                .param("t", req.title().trim())
                .param("d", req.description().trim())
                .param("m", req.minutes())
                .param("id", id)
                .update();
        return get(id);
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
