package com.hourhive.web;

import com.hourhive.api.Dtos.CategoryCount;
import com.hourhive.api.Dtos.LeaderboardEntry;
import com.hourhive.service.ListingService;
import com.hourhive.service.StatsService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicController {

    private final StatsService stats;
    private final ListingService listings;

    public PublicController(StatsService stats, ListingService listings) {
        this.stats = stats;
        this.listings = listings;
    }

    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of("service", "HourHive API", "docs", "/swagger-ui.html", "ping", "/api/ping");
    }

    /** No database call on purpose: lets Render stay awake without waking Neon. */
    @GetMapping("/api/ping")
    public Map<String, Object> ping() {
        return Map.of("status", "ok", "time", Instant.now().toString());
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "database", stats.databaseUp() ? "up" : "down");
    }

    @GetMapping("/api/leaderboard")
    public List<LeaderboardEntry> leaderboard() {
        return stats.leaderboard();
    }

    @GetMapping("/api/categories")
    public List<CategoryCount> categories() {
        return listings.categories();
    }
}
