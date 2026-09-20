package com.hourhive.service;

import com.hourhive.api.Dtos.ListingView;
import com.hourhive.api.Dtos.ProfileView;
import com.hourhive.api.Dtos.ReviewView;
import com.hourhive.error.ApiException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Public member profile: bio, reputation, active listings and recent reviews. */
@Service
public class ProfileService {

    private final JdbcClient jdbc;
    private final ListingService listings;

    public ProfileService(JdbcClient jdbc, ListingService listings) {
        this.jdbc = jdbc;
        this.listings = listings;
    }

    public ProfileView get(long userId) {
        record U(String name, String bio, Instant since) {
        }
        U user = jdbc.sql("select display_name, bio, created_at from users where id = :id")
                .param("id", userId)
                .query((rs, n) -> new U(rs.getString("display_name"), rs.getString("bio"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Member not found"));

        double[] rating = jdbc.sql("""
                select coalesce(cast(avg(rating) as double precision), 0) as avg_rating, count(*) as cnt
                from reviews where reviewee_id = :id
                """)
                .param("id", userId)
                .query((rs, n) -> new double[] {rs.getDouble("avg_rating"), rs.getLong("cnt")})
                .single();

        long[] given = jdbc.sql("""
                select coalesce(sum(minutes), 0) as given, count(*) as sessions
                from bookings where provider_id = :id and status = 'COMPLETED'
                """)
                .param("id", userId)
                .query((rs, n) -> new long[] {rs.getLong("given"), rs.getLong("sessions")})
                .single();

        List<ReviewView> reviews = jdbc.sql("""
                select r.id, u.display_name, r.rating, r.comment, l.title, r.created_at
                from reviews r
                join users u on u.id = r.reviewer_id
                join bookings b on b.id = r.booking_id
                join listings l on l.id = b.listing_id
                where r.reviewee_id = :id
                order by r.id desc limit 20
                """)
                .param("id", userId)
                .query((rs, n) -> new ReviewView(
                        rs.getLong("id"), rs.getString("display_name"), rs.getInt("rating"),
                        rs.getString("comment"), rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();

        List<ListingView> active = listings.activeByOwner(userId);
        return new ProfileView(
                userId, user.name(), user.bio(),
                Math.round(rating[0] * 10.0) / 10.0, (int) rating[1],
                (int) given[0], (int) given[1], user.since(), active, reviews);
    }
}
