package com.hourhive.service;

import com.hourhive.api.Dtos.PageResponse;
import com.hourhive.api.Dtos.ReportRequest;
import com.hourhive.api.Dtos.ReportView;
import com.hourhive.api.Dtos.ResolveReportRequest;
import com.hourhive.domain.Role;
import com.hourhive.error.ApiException;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Members report users or listings; moderators resolve the reports. */
@Service
public class ReportService {

    private static final String SELECT = """
            select r.id, r.reporter_id, ru.display_name as reporter_name, r.target_type, r.target_id,
                   case when r.target_type = 'USER'
                        then (select display_name from users where id = r.target_id)
                        else (select title from listings where id = r.target_id) end as label,
                   r.reason, r.details, r.status, r.resolution_note, r.created_at, r.resolved_at
            from reports r join users ru on ru.id = r.reporter_id
            """;

    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final AuditService audit;

    public ReportService(JdbcClient jdbc, NotificationService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional
    public ReportView create(long reporterId, ReportRequest req) {
        String type = req.targetType().trim().toUpperCase(Locale.ROOT);
        if (!type.equals("USER") && !type.equals("LISTING")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "targetType must be USER or LISTING", "VALIDATION_FAILED");
        }
        long targetId = req.targetId();
        if (type.equals("USER")) {
            if (targetId == reporterId) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "You can't report yourself");
            }
            requireExists("select count(*) from users where id = :id", targetId, "Member not found");
        } else {
            requireExists("select count(*) from listings where id = :id", targetId, "Listing not found");
        }
        Long dup = jdbc.sql("""
                select count(*) from reports
                where reporter_id = :r and target_type = :t and target_id = :i and status = 'OPEN'
                """)
                .param("r", reporterId).param("t", type).param("i", targetId).query(Long.class).single();
        if (dup > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "You already have an open report for this", "DUPLICATE_REPORT");
        }
        long id = jdbc.sql("""
                insert into reports (reporter_id, target_type, target_id, reason, details)
                values (:r, :t, :i, :reason, :d) returning id
                """)
                .param("r", reporterId).param("t", type).param("i", targetId)
                .param("reason", req.reason().trim()).param("d", req.details())
                .query(Long.class).single();
        audit.record(reporterId, "REPORT_CREATED", "report", id, type + ":" + targetId);
        return get(id);
    }

    public PageResponse<ReportView> page(String status, int page, int size) {
        String st = status == null || status.isBlank() ? "OPEN" : status.trim().toUpperCase(Locale.ROOT);
        int s = Math.min(Math.max(size, 1), 50);
        int p = Math.max(page, 0);
        Long total = jdbc.sql("select count(*) from reports where status = :st")
                .param("st", st).query(Long.class).single();
        List<ReportView> items = jdbc.sql(SELECT + " where r.status = :st order by r.id desc limit :lim offset :off")
                .param("st", st).param("lim", s).param("off", p * s)
                .query((rs, n) -> map(rs)).list();
        return PageResponse.of(items, p, s, total);
    }

    public ReportView get(long id) {
        return jdbc.sql(SELECT + " where r.id = :id").param("id", id)
                .query((rs, n) -> map(rs)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Report not found"));
    }

    /** action: DISMISS, REMOVE_LISTING (listing reports) or SUSPEND_USER (user reports). */
    @Transactional
    public ReportView resolve(long moderatorId, long reportId, ResolveReportRequest req) {
        record R(String type, long targetId, String status, long reporterId) {
        }
        R r = jdbc.sql("select target_type, target_id, status, reporter_id from reports where id = :id for update")
                .param("id", reportId)
                .query((rs, n) -> new R(rs.getString("target_type"), rs.getLong("target_id"),
                        rs.getString("status"), rs.getLong("reporter_id")))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Report not found"));
        if (!r.status().equals("OPEN")) {
            throw new ApiException(HttpStatus.CONFLICT, "Report is already " + r.status(), "INVALID_STATE");
        }
        String action = req.action().trim().toUpperCase(Locale.ROOT);
        String newStatus = "RESOLVED";
        switch (action) {
            case "DISMISS" -> newStatus = "DISMISSED";
            case "REMOVE_LISTING" -> {
                if (!r.type().equals("LISTING")) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "REMOVE_LISTING only applies to listing reports",
                            "VALIDATION_FAILED");
                }
                jdbc.sql("update listings set active = false where id = :id").param("id", r.targetId()).update();
                audit.record(moderatorId, "LISTING_REMOVED", "listing", r.targetId(), "via report " + reportId);
            }
            case "SUSPEND_USER" -> {
                if (!r.type().equals("USER")) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "SUSPEND_USER only applies to user reports",
                            "VALIDATION_FAILED");
                }
                String targetRole = jdbc.sql("select role from users where id = :id").param("id", r.targetId())
                        .query(String.class).optional().orElse("USER");
                if (Role.valueOf(targetRole).atLeast(Role.MODERATOR) || r.targetId() == moderatorId) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "You can't suspend staff accounts", "FORBIDDEN");
                }
                jdbc.sql("update users set disabled = true where id = :id").param("id", r.targetId()).update();
                jdbc.sql("update listings set active = false where owner_id = :id").param("id", r.targetId()).update();
                audit.record(moderatorId, "USER_SUSPENDED", "user", r.targetId(), "via report " + reportId);
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST,
                    "action must be DISMISS, REMOVE_LISTING or SUSPEND_USER", "VALIDATION_FAILED");
        }
        jdbc.sql("""
                update reports set status = :s, resolved_by = :m, resolution_note = :n, resolved_at = now()
                where id = :id
                """)
                .param("s", newStatus).param("m", moderatorId).param("n", req.note()).param("id", reportId)
                .update();
        notifications.notify(r.reporterId(), "REPORT_REVIEWED", "Thanks, a moderator reviewed your report", "#/");
        audit.record(moderatorId, "REPORT_" + newStatus, "report", reportId, action);
        return get(reportId);
    }

    private void requireExists(String sql, long id, String message) {
        Long n = jdbc.sql(sql).param("id", id).query(Long.class).single();
        if (n == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, message);
        }
    }

    private static ReportView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp resolved = rs.getTimestamp("resolved_at");
        return new ReportView(rs.getLong("id"), rs.getLong("reporter_id"), rs.getString("reporter_name"),
                rs.getString("target_type"), rs.getLong("target_id"), rs.getString("label"),
                rs.getString("reason"), rs.getString("details"), rs.getString("status"),
                rs.getString("resolution_note"), rs.getTimestamp("created_at").toInstant(),
                resolved == null ? null : resolved.toInstant());
    }
}
