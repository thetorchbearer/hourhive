package com.hourhive.service;

import com.hourhive.api.Dtos.MessageRequest;
import com.hourhive.api.Dtos.MessageView;
import com.hourhive.error.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Simple message thread attached to a booking. Only the learner and the provider can read or write. */
@Service
public class MessageService {

    private static final int MAX_MESSAGES_PER_BOOKING = 300;

    private final JdbcClient jdbc;

    public MessageService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<MessageView> list(long userId, long bookingId) {
        requireParticipant(userId, bookingId);
        return jdbc.sql("""
                select m.id, m.sender_id, u.display_name, m.body, m.created_at
                from booking_messages m join users u on u.id = m.sender_id
                where m.booking_id = :b order by m.id asc limit 300
                """)
                .param("b", bookingId)
                .query((rs, n) -> new MessageView(
                        rs.getLong("id"), rs.getLong("sender_id"), rs.getString("display_name"),
                        rs.getString("body"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public MessageView send(long userId, long bookingId, MessageRequest req) {
        requireParticipant(userId, bookingId);
        Long count = jdbc.sql("select count(*) from booking_messages where booking_id = :b")
                .param("b", bookingId).query(Long.class).single();
        if (count >= MAX_MESSAGES_PER_BOOKING) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "This conversation has reached its message limit");
        }
        long id = jdbc.sql("""
                insert into booking_messages (booking_id, sender_id, body)
                values (:b, :s, :t) returning id
                """)
                .param("b", bookingId)
                .param("s", userId)
                .param("t", req.body().trim())
                .query(Long.class)
                .single();
        return jdbc.sql("""
                select m.id, m.sender_id, u.display_name, m.body, m.created_at
                from booking_messages m join users u on u.id = m.sender_id
                where m.id = :id
                """)
                .param("id", id)
                .query((rs, n) -> new MessageView(
                        rs.getLong("id"), rs.getLong("sender_id"), rs.getString("display_name"),
                        rs.getString("body"), rs.getTimestamp("created_at").toInstant()))
                .single();
    }

    private void requireParticipant(long userId, long bookingId) {
        record P(long learner, long provider) {
        }
        P p = jdbc.sql("select learner_id, provider_id from bookings where id = :id")
                .param("id", bookingId)
                .query((rs, n) -> new P(rs.getLong("learner_id"), rs.getLong("provider_id")))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Booking not found"));
        if (p.learner() != userId && p.provider() != userId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Not your booking");
        }
    }
}
