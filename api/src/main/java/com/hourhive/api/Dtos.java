package com.hourhive.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class Dtos {
    private Dtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 60) String displayName,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record ProfileRequest(
            @NotBlank @Size(max = 60) String displayName,
            @Size(max = 500) String bio) {
    }

    public record UserView(long id, String email, String displayName, String bio, int balanceMinutes) {
    }

    public record AuthResponse(String token, UserView user) {
    }

    public record ListingRequest(
            @NotBlank @Size(max = 40) String category,
            @NotBlank @Size(max = 100) String title,
            @NotBlank @Size(max = 1000) String description,
            @Min(15) @Max(240) int minutes) {
    }

    public record ListingView(
            long id, long ownerId, String ownerName, double ownerRating, int ownerReviews,
            String category, String title, String description, int minutes,
            boolean active, Instant createdAt) {
    }

    public record BookingRequest(@NotNull Long listingId, @Size(max = 500) String note) {
    }

    public record BookingView(
            long id, long listingId, String listingTitle,
            long learnerId, String learnerName, long providerId, String providerName,
            int minutes, String status, String note, boolean reviewed,
            Instant createdAt, Instant updatedAt) {
    }

    public record ReviewRequest(@Min(1) @Max(5) int rating, @Size(max = 500) String comment) {
    }

    public record LedgerView(long id, int deltaMinutes, String kind, Long bookingId, String note, Instant createdAt) {
    }

    public record LeaderboardEntry(long userId, String displayName, int minutesGiven, int sessions, double rating) {
    }

    public record CategoryCount(String category, int listings) {
    }

    public record MessageRequest(@NotBlank @Size(max = 1000) String body) {
    }

    public record MessageView(long id, long senderId, String senderName, String body, Instant createdAt) {
    }

    public record ReviewView(long id, String reviewerName, int rating, String comment,
                             String listingTitle, Instant createdAt) {
    }

    public record ProfileView(
            long id, String displayName, String bio, double rating, int reviewCount,
            int minutesGiven, int sessions, Instant memberSince,
            List<ListingView> listings, List<ReviewView> reviews) {
    }

    public record CommunityStats(int members, int activeListings, int completedSessions, int minutesExchanged) {
    }

    public record MeSummary(int pendingRequests, int acceptedSessions) {
    }
}
