package com.hourhive.service;

import com.hourhive.api.Dtos.BookingRequest;
import com.hourhive.api.Dtos.BookingView;
import com.hourhive.api.Dtos.ReviewRequest;
import com.hourhive.error.ApiException;
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
                   (rv.id is not null) as reviewed, b.created_at, b.updated_at
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

    public BookingService(JdbcClient jdbc, LedgerService ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
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

        long id = jdbc.sql("""
                insert into bookings (listing_id, learner_id, provider_id, minutes, status, note)
                values (:l, :lr, :p, :m, 'REQUESTED', :n) returning id
                """)
                .param("l", req.listingId())
                .param("lr", learnerId)
                .param("p", listing.ownerId())
                .param("m", listing.minutes())
                .param("n", req.note())
                .query(Long.class)
                .single();
        ledger.append(learnerId, -listing.minutes(), "ESCROW", id, "Held for: " + listing.title());
        return get(id, learnerId);
    }

    @Transactional
    public BookingView accept(long userId, long id) {
        Row b = lock(id);
        requireProvider(b, userId);
        requireStatus(b, "REQUESTED");
        setStatus(id, "ACCEPTED");
        return get(id, userId);
    }

    @Transactional
    public BookingView decline(long userId, long id) {
        Row b = lock(id);
        requireProvider(b, userId);
        requireStatus(b, "REQUESTED");
        setStatus(id, "DECLINED");
        refund(b, "Declined: " + title(b.listingId()));
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
    }

    public List<BookingView> mine(long userId) {
        return jdbc.sql(SELECT + " where b.learner_id = :u or b.provider_id = :u order by b.updated_at desc limit 100")
                .param("u", userId)
                .query((rs, n) -> map(rs))
                .list();
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
        return new BookingView(
                rs.getLong("id"), rs.getLong("listing_id"), rs.getString("title"),
                rs.getLong("learner_id"), rs.getString("learner_name"),
                rs.getLong("provider_id"), rs.getString("provider_name"),
                rs.getInt("minutes"), rs.getString("status"), rs.getString("note"),
                rs.getBoolean("reviewed"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
