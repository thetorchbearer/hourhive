package com.hourhive.service;

import com.hourhive.api.Dtos.NotificationView;
import com.hourhive.api.Dtos.PageResponse;
import com.hourhive.error.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final JdbcClient jdbc;

    public NotificationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void notify(long userId, String kind, String message, String link) {
        String msg = message.length() > 300 ? message.substring(0, 297) + "..." : message;
        jdbc.sql("insert into notifications (user_id, kind, message, link) values (:u, :k, :m, :l)")
                .param("u", userId).param("k", kind).param("m", msg).param("l", link)
                .update();
    }

    public PageResponse<NotificationView> page(long userId, int page, int size) {
        int s = Math.min(Math.max(size, 1), 50);
        int p = Math.max(page, 0);
        Long total = jdbc.sql("select count(*) from notifications where user_id = :u")
                .param("u", userId).query(Long.class).single();
        List<NotificationView> items = jdbc.sql("""
                select id, kind, message, link, is_read, created_at
                from notifications where user_id = :u order by id desc limit :lim offset :off
                """)
                .param("u", userId).param("lim", s).param("off", p * s)
                .query((rs, n) -> new NotificationView(
                        rs.getLong("id"), rs.getString("kind"), rs.getString("message"), rs.getString("link"),
                        rs.getBoolean("is_read"), rs.getTimestamp("created_at").toInstant()))
                .list();
        return PageResponse.of(items, p, s, total);
    }

    public int unreadCount(long userId) {
        return jdbc.sql("select count(*) from notifications where user_id = :u and is_read = false")
                .param("u", userId).query(Long.class).single().intValue();
    }

    public void markRead(long userId, long id) {
        int n = jdbc.sql("update notifications set is_read = true where id = :id and user_id = :u")
                .param("id", id).param("u", userId).update();
        if (n == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Notification not found");
        }
    }

    public void markAllRead(long userId) {
        jdbc.sql("update notifications set is_read = true where user_id = :u and is_read = false")
                .param("u", userId).update();
    }
}
