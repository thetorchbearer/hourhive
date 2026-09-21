package com.hourhive.service;

import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Append-only record of who did what. Runs inside the caller's transaction. */
@Service
public class AuditService {

    private final JdbcClient jdbc;

    public AuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Long actorId, String action, String entityType, Long entityId, String detail) {
        jdbc.sql("""
                insert into audit_log (actor_id, action, entity_type, entity_id, detail, request_id)
                values (:a, :act, :et, :eid, :d, :rid)
                """)
                .param("a", actorId)
                .param("act", action)
                .param("et", entityType)
                .param("eid", entityId)
                .param("d", detail == null ? null : (detail.length() > 500 ? detail.substring(0, 500) : detail))
                .param("rid", MDC.get("requestId"))
                .update();
    }
}
