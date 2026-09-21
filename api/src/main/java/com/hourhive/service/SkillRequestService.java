package com.hourhive.service;

import com.hourhive.api.Dtos.HelperMatch;
import com.hourhive.api.Dtos.PageResponse;
import com.hourhive.api.Dtos.SkillRequestRequest;
import com.hourhive.api.Dtos.SkillRequestView;
import com.hourhive.error.ApiException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Members post what they need; matching helpers are ranked and notified. */
@Service
public class SkillRequestService {

    private static final String SELECT = """
            select r.id, r.requester_id, u.display_name, r.category, r.title, r.description,
                   r.minutes, r.status, r.created_at
            from skill_requests r join users u on u.id = r.requester_id
            """;

    private final JdbcClient jdbc;
    private final ListingService listings;
    private final NotificationService notifications;
    private final AuditService audit;

    public SkillRequestService(JdbcClient jdbc, ListingService listings,
                               NotificationService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.listings = listings;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional
    public SkillRequestView create(long userId, SkillRequestRequest req) {
        Long open = jdbc.sql("select count(*) from skill_requests where requester_id = :u and status = 'OPEN'")
                .param("u", userId).query(Long.class).single();
        if (open >= 10) {
            throw new ApiException(HttpStatus.CONFLICT, "You already have 10 open requests. Close one first.",
                    "LIMIT_REACHED");
        }
        long id = jdbc.sql("""
                insert into skill_requests (requester_id, category, title, description, minutes)
                values (:u, :c, :t, :d, :m) returning id
                """)
                .param("u", userId)
                .param("c", req.category().trim())
                .param("t", req.title().trim())
                .param("d", req.description().trim())
                .param("m", req.minutes())
                .query(Long.class)
                .single();
        audit.record(userId, "REQUEST_CREATED", "skill_request", id, req.title().trim());

        Set<Long> notified = new HashSet<>();
        for (HelperMatch m : matchesFor(userId, req.category(), req.title(), req.description())) {
            if (notified.size() >= 5) {
                break;
            }
            if (notified.add(m.ownerId())) {
                notifications.notify(m.ownerId(), "SKILL_REQUEST",
                        "Someone is looking for help: " + req.title().trim(), "#/requests");
            }
        }
        return get(id);
    }

    public PageResponse<SkillRequestView> page(String q, String category, String status, String sort,
                                               int page, int size) {
        StringBuilder where = new StringBuilder(" where r.status = :st and u.disabled = false");
        Map<String, Object> params = new HashMap<>();
        params.put("st", status == null || status.isBlank() ? "OPEN" : status.trim().toUpperCase(Locale.ROOT));
        if (category != null && !category.isBlank()) {
            where.append(" and r.category = :cat");
            params.put("cat", category.trim());
        }
        if (q != null && !q.isBlank()) {
            where.append(" and (lower(r.title) like :q or lower(r.description) like :q)");
            params.put("q", "%" + q.trim().toLowerCase(Locale.ROOT) + "%");
        }
        String order = "oldest".equals(sort) ? " order by r.created_at asc" : " order by r.created_at desc";
        int s = Math.min(Math.max(size, 1), 50);
        int p = Math.max(page, 0);
        Long total = jdbc.sql("select count(*) from skill_requests r join users u on u.id = r.requester_id" + where)
                .params(params).query(Long.class).single();
        Map<String, Object> pp = new HashMap<>(params);
        pp.put("lim", s);
        pp.put("off", p * s);
        List<SkillRequestView> items = jdbc.sql(SELECT + where + order + " limit :lim offset :off")
                .params(pp).query((rs, n) -> map(rs)).list();
        return PageResponse.of(items, p, s, total);
    }

    public List<SkillRequestView> mine(long userId) {
        return jdbc.sql(SELECT + " where r.requester_id = :u order by r.created_at desc limit 100")
                .param("u", userId).query((rs, n) -> map(rs)).list();
    }

    public SkillRequestView get(long id) {
        return jdbc.sql(SELECT + " where r.id = :id")
                .param("id", id).query((rs, n) -> map(rs)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Request not found"));
    }

    @Transactional
    public SkillRequestView close(long userId, long id) {
        SkillRequestView r = get(id);
        if (r.requesterId() != userId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the requester can close this");
        }
        if (!"OPEN".equals(r.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Request is already " + r.status());
        }
        jdbc.sql("update skill_requests set status = 'CLOSED' where id = :id").param("id", id).update();
        audit.record(userId, "REQUEST_CLOSED", "skill_request", id, null);
        return get(id);
    }

    public List<HelperMatch> matches(long id) {
        SkillRequestView r = get(id);
        return matchesFor(r.requesterId(), r.category(), r.title(), r.description());
    }

    private List<HelperMatch> matchesFor(long requesterId, String category, String title, String description) {
        return HelperMatcher.rank(category, title, description, listings.candidates(requesterId), 10);
    }

    private static SkillRequestView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SkillRequestView(rs.getLong("id"), rs.getLong("requester_id"), rs.getString("display_name"),
                rs.getString("category"), rs.getString("title"), rs.getString("description"),
                rs.getInt("minutes"), rs.getString("status"), rs.getTimestamp("created_at").toInstant());
    }
}
