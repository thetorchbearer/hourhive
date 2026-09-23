package com.hourhive.service;

import com.hourhive.api.Dtos.AdminOverview;
import com.hourhive.api.Dtos.AdminUserView;
import com.hourhive.api.Dtos.AuditView;
import com.hourhive.api.Dtos.LedgerReport;
import com.hourhive.api.Dtos.LedgerViolation;
import com.hourhive.api.Dtos.PageResponse;
import com.hourhive.domain.Role;
import com.hourhive.error.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Staff tooling: overview, user management, audit trail and ledger verification. */
@Service
public class AdminService {

    private final JdbcClient jdbc;
    private final RoleService roles;
    private final AuditService audit;

    public AdminService(JdbcClient jdbc, RoleService roles, AuditService audit) {
        this.jdbc = jdbc;
        this.roles = roles;
        this.audit = audit;
    }

    public AdminOverview overview() {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        jdbc.sql("select status, count(*) as n from bookings group by status order by status")
                .query((rs, i) -> {
                    byStatus.put(rs.getString("status"), (int) rs.getLong("n"));
                    return 0;
                }).list();
        return jdbc.sql("""
                select (select count(*) from users) as users,
                       (select count(*) from users where disabled = true) as suspended,
                       (select count(*) from reports where status = 'OPEN') as open_reports,
                       (select count(*) from listings where active = true) as listings,
                       (select count(*) from skill_requests where status = 'OPEN') as open_requests,
                       (select coalesce(sum(minutes), 0) from bookings where status = 'COMPLETED') as minutes
                """)
                .query((rs, n) -> new AdminOverview((int) rs.getLong("users"), (int) rs.getLong("suspended"),
                        (int) rs.getLong("open_reports"), (int) rs.getLong("listings"),
                        (int) rs.getLong("open_requests"), byStatus, (int) rs.getLong("minutes")))
                .single();
    }

    public PageResponse<AdminUserView> users(String q, int page, int size) {
        int s = Math.min(Math.max(size, 1), 50);
        int p = Math.max(page, 0);
        String like = "%" + (q == null ? "" : q.trim().toLowerCase(Locale.ROOT)) + "%";
        Long total = jdbc.sql("select count(*) from users where lower(email) like :q or lower(display_name) like :q")
                .param("q", like).query(Long.class).single();
        List<AdminUserView> items = jdbc.sql("""
                select id, email, display_name, role, disabled, created_at
                from users where lower(email) like :q or lower(display_name) like :q
                order by id desc limit :lim offset :off
                """)
                .param("q", like).param("lim", s).param("off", p * s)
                .query((rs, n) -> new AdminUserView(rs.getLong("id"), rs.getString("email"),
                        rs.getString("display_name"), rs.getString("role"),
                        rs.getBoolean("disabled"), rs.getTimestamp("created_at").toInstant()))
                .list();
        return PageResponse.of(items, p, s, total);
    }

    @Transactional
    public void changeRole(long adminId, long targetId, String role) {
        Role newRole;
        try {
            newRole = Role.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "role must be USER, MODERATOR or ADMIN", "VALIDATION_FAILED");
        }
        if (targetId == adminId && newRole != Role.ADMIN) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You can't demote yourself", "VALIDATION_FAILED");
        }
        roles.setRole(targetId, newRole);
        audit.record(adminId, "ROLE_CHANGED", "user", targetId, newRole.name());
    }

    @Transactional
    public void setSuspended(long adminId, long targetId, boolean value) {
        if (targetId == adminId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You can't suspend yourself", "VALIDATION_FAILED");
        }
        String targetRole = jdbc.sql("select role from users where id = :id").param("id", targetId)
                .query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Member not found"));
        if (value && Role.valueOf(targetRole) == Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Demote the admin first", "FORBIDDEN");
        }
        jdbc.sql("update users set disabled = :v where id = :id").param("v", value).param("id", targetId).update();
        if (value) {
            jdbc.sql("update listings set active = false where owner_id = :id").param("id", targetId).update();
        }
        audit.record(adminId, value ? "USER_SUSPENDED" : "USER_REINSTATED", "user", targetId, null);
    }

    public PageResponse<AuditView> audit(String action, int page, int size) {
        int s = Math.min(Math.max(size, 1), 100);
        int p = Math.max(page, 0);
        boolean filter = action != null && !action.isBlank();
        String where = filter ? " where a.action = :act" : "";
        Map<String, Object> params = new HashMap<>();
        if (filter) {
            params.put("act", action.trim().toUpperCase(Locale.ROOT));
        }
        Long total = jdbc.sql("select count(*) from audit_log a" + where).params(params).query(Long.class).single();
        Map<String, Object> pp = new HashMap<>(params);
        pp.put("lim", s);
        pp.put("off", p * s);
        List<AuditView> items = jdbc.sql("""
                select a.id, a.actor_id, u.display_name, a.action, a.entity_type, a.entity_id,
                       a.detail, a.request_id, a.created_at
                from audit_log a left join users u on u.id = a.actor_id
                """ + where + " order by a.id desc limit :lim offset :off")
                .params(pp)
                .query((rs, n) -> new AuditView(rs.getLong("id"), rs.getObject("actor_id", Long.class),
                        rs.getString("display_name"), rs.getString("action"), rs.getString("entity_type"),
                        rs.getObject("entity_id", Long.class), rs.getString("detail"), rs.getString("request_id"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        return PageResponse.of(items, p, s, total);
    }

    /**
     * Ledger integrity check. For every booking the learner's net ledger movement must be
     * -minutes while the booking is open or completed, and 0 once declined or cancelled (refunded);
     * the provider must have earned exactly +minutes when completed and nothing otherwise.
     * Also flags any user whose ledger sums to a negative balance, which should never happen.
     */
    public LedgerReport verifyLedger() {
        List<LedgerViolation> violations = new ArrayList<>();
        int[] checked = {0};
        jdbc.sql("""
                select b.id, b.status, b.minutes,
                       coalesce((select sum(e.delta_minutes) from ledger_entries e
                                 where e.booking_id = b.id and e.user_id = b.learner_id), 0) as learner_net,
                       coalesce((select sum(e.delta_minutes) from ledger_entries e
                                 where e.booking_id = b.id and e.user_id = b.provider_id), 0) as provider_net
                from bookings b
                """)
                .query((rs, n) -> {
                    checked[0]++;
                    String status = rs.getString("status");
                    long minutes = rs.getLong("minutes");
                    long learnerNet = rs.getLong("learner_net");
                    long providerNet = rs.getLong("provider_net");
                    boolean refunded = status.equals("DECLINED") || status.equals("CANCELLED");
                    long expectedLearner = refunded ? 0 : -minutes;
                    long expectedProvider = status.equals("COMPLETED") ? minutes : 0;
                    if (learnerNet != expectedLearner) {
                        violations.add(new LedgerViolation(rs.getLong("id"), status,
                                "learner net " + learnerNet + " but expected " + expectedLearner));
                    }
                    if (providerNet != expectedProvider) {
                        violations.add(new LedgerViolation(rs.getLong("id"), status,
                                "provider net " + providerNet + " but expected " + expectedProvider));
                    }
                    return 0;
                }).list();
        List<Long> negative = jdbc.sql("""
                select user_id from ledger_entries group by user_id having sum(delta_minutes) < 0 order by user_id
                """).query(Long.class).list();
        return new LedgerReport(violations.isEmpty() && negative.isEmpty(), checked[0], violations, negative);
    }
}
