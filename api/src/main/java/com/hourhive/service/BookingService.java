package com.hourhive.service;

import com.hourhive.api.Dtos.BookingRequest;
import com.hourhive.api.Dtos.BookingView;
import com.hourhive.api.Dtos.MeSummary;
import com.hourhive.api.Dtos.ReviewRequest;
import com.hourhive.error.ApiException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking lifecycle with escrow:
 * REQUESTED  -> learner's minutes are held (negative ledger row)
 * ACCEPTED   -> provider agreed
 * COMPLETED  -> learner confirms; provider is paid from the escrow
 * DECLINED / CANCELLED -> escrow is refunded exactly once (guarded by a row lock)
 */
@Service
public class BookingService {

    private static final String SELECT = """
            select b.id, b.listing_id, l.title, b.learner_id, lu.display_name as learner_name,
                   b.provider_id, pu.display_name as provider_name, b.minutes, b.status, b.note,
                   (rv.id is not null) as reviewed, b.created_at, b.updated_at,
                   b.scheduled_at, b.reschedule_count
            from bookings b
            join listings l on l.id = b.listing_id
            join users lu on lu.id = b.learner_id
            join users pu on pu.id = b.provider_id
            left join reviews rv on rv.booking_id = b.id
            """;

    private record Row(long id, long listingId, long learnerId, long providerId, int minutes, String status) {
    }

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final AvailabilityService availability;
    private final NotificationService notifications;
    private final AuditService audit;

    public BookingService(JdbcClient jdbc, LedgerService ledger, AvailabilityService availability,
                          NotificationService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.availability = availability;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional
    public BookingView request(long learnerId, BookingRequest req) {
        // Serialise balance checks per learner so two parallel requests can't overspend.
        jdbc.sql("select id from users where id = :id for update").param("id", learnerId)
                .query(Long.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));

        record L(long ownerId, int minutes, boolean active, String title) {
        }
        L listing = jdbc.sql("select owner_id, minutes, active, title from listings where id = :id")
                .param("id", req.listingId())
                .query((rs, n) -> new L(rs.getLong("owner_id"), rs.getInt("minutes"),
                        rs.getBoolean("active"), rs.getString("title")))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Listing not found"));

        if (!listing.active()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "That listing is no longer available");
        }
        if (listing.ownerId() == learnerId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You can't book your own listing");
        }
        if (ledger.balance(learnerId) < listing.minutes()) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED,
                    "Not enough time credits. Offer a skill to earn more hours!");
        }
        Long open = jdbc.sql("""
                select count(*) from bookings
                where listing_id = :l and learner_id = :u and status in ('REQUESTED', 'ACCEPTED')
                """)
                .param("l", req.listingId()).param("u", learnerId).query(Long.class).single();
        if (open > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "You already have an open booking for this listing");
        }
        Instant when = req.scheduledAt();
        if (when != null) {
            validateSchedule(listing.ownerId(), learnerId, listing.minutes(), when, 0L);
        }

        long id = jdbc.sql("""
                insert into bookings (listing_id, learner_id, provider_id, minutes, status, note, scheduled_at)
                values (:l, :lr, :p, :m, 'REQUESTED', :n, :s) returning id
                """)
                .param("l", req.listingId())
                .param("lr", learnerId)
                .param("p", listing.ownerId())
                .param("m", listing.minutes())
                .param("n", req.note())
                .param("s", when == null ? null : Timestamp.from(when))
                .query(Long.class)
                .single();
        ledger.append(learnerId, -listing.minutes(), "ESCROW", id, "Held for: " + listing.title());
        notifications.notify(listing.ownerId(), "BOOKING_REQUESTED",
                "New booking request for \"" + listing.title() + "\"", "#/bookings");
        audit.record(learnerId, "BOOKING_REQUESTED", "booking", id, listing.title());
        return get(id, learnerId);
    }

    @Transactional
    public BookingView accept(long userId, long id) {
        Row b = lock(id);
        requireProvider(b, userId);
        requireStatus(b, "REQUESTED");
        setStatus(id, "ACCEPTED");
        notifications.notify(b.learnerId(), "BOOKING_ACCEPTED", "Your booking was accepted", "#/bookings");
        audit.record(userId, "BOOKING_ACCEPTED", "booking", id, null);
        return get(id, userId);
    }

    @Transactional
    public BookingView decline(long userId, long id) {
        Row b = lock(id);
        requireProvider(b, userId);
        requireStatus(b, "REQUESTED");
        setStatus(id, "DECLINED");
        refund(b, "Declined: " + title(b.listingId()));
        notifications.notify(b.learnerId(), "BOOKING_DECLINED",
                "Your booking was declined and your minutes were refunded", "#/bookings");
        audit.record(userId, "BOOKING_DECLINED", "booking", id, null);
        return get(id, userId);
    }

    @Transactional
    public BookingView cancel(long userId, long id) {
        Row b = lock(id);
        boolean learner = b.learnerId() == userId;
        boolean provider = b.providerId() == userId;
        if (!learner && !provider) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Not your booking");
        }
        boolean open = b.status().equals("REQUESTED") || b.status().equals("ACCEPTED");
        if (!open) {
            throw new ApiException(HttpStatus.CONFLICT, "This booking can no longer be cancelled");
        }
        setStatus(id, "CANCELLED");
        refund(b, "Cancelled: " + title(b.listingId()));
        long other = learner ? b.providerId() : b.learnerId();
        notifications.notify(other, "BOOKING_CANCELLED", "A booking was cancelled", "#/bookings");
        audit.record(userId, "BOOKING_CANCELLED", "booking", id, null);
        return get(id, userId);
    }

    @Transactional
    public BookingView complete(long userId, long id) {
        Row b = lock(id);
        if (b.learnerId() != userId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the learner can confirm a finished session");
        }
        requireStatus(b, "ACCEPTED");
        setStatus(id, "COMPLETED");
        ledger.append(b.providerId(), b.minutes(), "EARNED", id, "Session completed: " + title(b.listingId()));
        notifications.notify(b.providerId(), "BOOKING_COMPLETED",
                "Session completed. You earned " + b.minutes() + " minutes", "#/wallet");
        audit.record(userId, "BOOKING_COMPLETED", "booking", id, null);
        return get(id, userId);
    }

    /**
     * Either participant can move an open session to a new time. If the learner moves an ACCEPTED session,
     * it goes back to REQUESTED so the provider has to reconfirm. Escrow is untouched.
     */
    @Transactional
    public BookingView reschedule(long userId, long id, Instant when) {
        Row b = lock(id);
        boolean learner = b.learnerId() == userId;
        if (!learner && b.providerId() != userId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Not your booking");
        }
        boolean open = b.status().equals("REQUESTED") || b.status().equals("ACCEPTED");
        if (!open) {
            throw new ApiException(HttpStatus.CONFLICT, "Only open bookings can be rescheduled");
        }
        validateSchedule(b.providerId(), b.learnerId(), b.minutes(), when, b.id());
        boolean reconfirm = learner && b.status().equals("ACCEPTED");
        jdbc.sql("""
                update bookings
                set scheduled_at = :s, reschedule_count = reschedule_count + 1, updated_at = now(),
                    status = :st
                where id = :id
                """)
                .param("s", Timestamp.from(when))
                .param("st", reconfirm ? "REQUESTED" : b.status())
                .param("id", id)
                .update();
        long other = learner ? b.providerId() : b.learnerId();
        notifications.notify(other, "BOOKING_RESCHEDULED",
                "A session was rescheduled" + (reconfirm ? ". Please reconfirm." : ""), "#/bookings");
        audit.record(userId, "BOOKING_RESCHEDULED", "booking", id, when.toString());
        return get(id, userId);
    }

    @Transactional
    public void review(long userId, long id, ReviewRequest req) {
        Row b = lock(id);
        if (b.learnerId() != userId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the learner can review this session");
        }
        requireStatus(b, "COMPLETED");
        Long existing = jdbc.sql("select count(*) from reviews where booking_id = :id")
                .param("id", id).query(Long.class).single();
        if (existing > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "You already reviewed this session");
        }
        jdbc.sql("""
                insert into reviews (booking_id, reviewer_id, reviewee_id, rating, comment)
                values (:b, :r, :p, :s, :c)
                """)
                .param("b", id).param("r", userId).param("p", b.providerId())
                .param("s", req.rating()).param("c", req.comment())
                .update();
        notifications.notify(b.providerId(), "REVIEW_RECEIVED",
                "You received a " + req.rating() + "-star review", "#/u/" + b.providerId());
    }

    public List<BookingView> mine(long userId) {
        return jdbc.sql(SELECT + " where b.learner_id = :u or b.provider_id = :u order by b.updated_at desc limit 100")
                .param("u", userId)
                .query((rs, n) -> map(rs))
                .list();
    }

    public MeSummary summary(long userId) {
        return jdbc.sql("""
                select count(*) filter (where provider_id = :u and status = 'REQUESTED') as pending,
                       count(*) filter (where status = 'ACCEPTED') as accepted
                from bookings where learner_id = :u or provider_id = :u
                """)
                .param("u", userId)
                .query((rs, n) -> new MeSummary((int) rs.getLong("pending"), (int) rs.getLong("accepted")))
                .single();
    }

    public BookingView get(long id, long userId) {
        BookingView v = jdbc.sql(SELECT + " where b.id = :id")
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Booking not found"));
        if (v.learnerId() != userId && v.providerId() != userId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Not your booking");
        }
        return v;
    }

    // ---- helpers ----

    /** Future time, inside the provider's availability, and free of clashes for both people. */
    private void validateSchedule(long providerId, long learnerId, int minutes, Instant when, long excludeId) {
        Instant now = Instant.now();
        if (!when.isAfter(now.plus(5, ChronoUnit.MINUTES))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a time in the future", "INVALID_TIME");
        }
        if (when.isAfter(now.plus(Duration.ofDays(180)))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Sessions can be booked up to 180 days ahead",
                    "INVALID_TIME");
        }
        availability.requireWithin(providerId, when, minutes);
        Instant end = when.plus(minutes, ChronoUnit.MINUTES);
        Long clashes = jdbc.sql("""
                select count(*) from bookings
                where status in ('REQUESTED', 'ACCEPTED') and scheduled_at is not null
                  and (provider_id in (:p, :l) or learner_id in (:p, :l))
                  and id <> :ex
                  and scheduled_at < :endts
                  and scheduled_at + (minutes * interval '1 minute') > :startts
                """)
                .param("p", providerId).param("l", learnerId).param("ex", excludeId)
                .param("startts", Timestamp.from(when)).param("endts", Timestamp.from(end))
                .query(Long.class).single();
        if (clashes > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "That time overlaps another session for you or the provider", "SCHEDULE_CONFLICT");
        }
    }

    private Row lock(long id) {
        return jdbc.sql("""
                select id, listing_id, learner_id, provider_id, minutes, status
                from bookings where id = :id for update
                """)
                .param("id", id)
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getLong("listing_id"),
                        rs.getLong("learner_id"), rs.getLong("provider_id"),
                        rs.getInt("minutes"), rs.getString("status")))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Booking not found"));
    }

    private void requireProvider(Row b, long userId) {
        if (b.providerId() != userId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the provider can do that");
        }
    }

    private void requireStatus(Row b, String expected) {
        if (!b.status().equals(expected)) {
            throw new ApiException(HttpStatus.CONFLICT, "Booking is " + b.status() + ", expected " + expected);
        }
    }

    private void setStatus(long id, String status) {
        jdbc.sql("update bookings set status = :s, updated_at = now() where id = :id")
                .param("s", status).param("id", id).update();
    }

    private void refund(Row b, String note) {
        ledger.append(b.learnerId(), b.minutes(), "REFUND", b.id(), note);
    }

    private String title(long listingId) {
        return jdbc.sql("select title from listings where id = :id").param("id", listingId)
                .query(String.class).single();
    }

    private static BookingView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp sched = rs.getTimestamp("scheduled_at");
        return new BookingView(
                rs.getLong("id"), rs.getLong("listing_id"), rs.getString("title"),
                rs.getLong("learner_id"), rs.getString("learner_name"),
                rs.getLong("provider_id"), rs.getString("provider_name"),
                rs.getInt("minutes"), rs.getString("status"), rs.getString("note"),
                rs.getBoolean("reviewed"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                sched == null ? null : sched.toInstant(),
                rs.getInt("reschedule_count"));
    }
}
