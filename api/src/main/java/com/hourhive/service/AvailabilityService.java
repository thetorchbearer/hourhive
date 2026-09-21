package com.hourhive.service;

import com.hourhive.api.Dtos.SlotRequest;
import com.hourhive.api.Dtos.SlotView;
import com.hourhive.error.ApiException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Weekly recurring availability, expressed in UTC (1 = Monday ... 7 = Sunday). */
@Service
public class AvailabilityService {

    static final int MAX_SLOTS = 28;

    private final JdbcClient jdbc;

    public AvailabilityService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<SlotView> get(long userId) {
        return jdbc.sql("""
                select id, day_of_week, start_minute, end_minute
                from availability_slots where user_id = :u order by day_of_week, start_minute
                """)
                .param("u", userId)
                .query((rs, n) -> new SlotView(rs.getLong("id"), rs.getInt("day_of_week"),
                        rs.getInt("start_minute"), rs.getInt("end_minute")))
                .list();
    }

    @Transactional
    public List<SlotView> replace(long userId, List<SlotRequest> slots) {
        if (slots == null) {
            slots = List.of();
        }
        if (slots.size() > MAX_SLOTS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "At most " + MAX_SLOTS + " slots", "VALIDATION_FAILED");
        }
        for (SlotRequest s : slots) {
            boolean ok = s.dayOfWeek() >= 1 && s.dayOfWeek() <= 7
                    && s.startMinute() >= 0 && s.startMinute() < 1440
                    && s.endMinute() > s.startMinute() && s.endMinute() <= 1440;
            if (!ok) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Each slot needs a day 1-7 and a start before its end (minutes since midnight UTC)",
                        "VALIDATION_FAILED");
            }
        }
        jdbc.sql("delete from availability_slots where user_id = :u").param("u", userId).update();
        for (SlotRequest s : slots) {
            jdbc.sql("""
                    insert into availability_slots (user_id, day_of_week, start_minute, end_minute)
                    values (:u, :d, :s, :e)
                    """)
                    .param("u", userId).param("d", s.dayOfWeek())
                    .param("s", s.startMinute()).param("e", s.endMinute())
                    .update();
        }
        return get(userId);
    }

    /** Providers with no slots are treated as "flexible"; otherwise the session must fit inside one slot. */
    public void requireWithin(long providerId, Instant when, int minutes) {
        List<SlotView> slots = get(providerId);
        if (!fits(slots, when, minutes)) {
            throw new ApiException(HttpStatus.CONFLICT, "The provider isn't available at that time (UTC slots)",
                    "OUTSIDE_AVAILABILITY");
        }
    }

    public static boolean fits(List<SlotView> slots, Instant when, int minutes) {
        if (slots.isEmpty()) {
            return true;
        }
        ZonedDateTime z = when.atZone(ZoneOffset.UTC);
        int day = z.getDayOfWeek().getValue();
        int start = z.getHour() * 60 + z.getMinute();
        int end = start + minutes;
        return slots.stream().anyMatch(s -> s.dayOfWeek() == day && s.startMinute() <= start && s.endMinute() >= end);
    }
}
