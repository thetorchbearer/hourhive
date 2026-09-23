package com.hourhive.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hourhive.api.Dtos.HelperMatch;
import com.hourhive.api.Dtos.ListingView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HelperMatcherTest {

    private static ListingView listing(long ownerId, String ownerName, String category, String title,
                                       String description, double rating, int reviews) {
        return new ListingView(ownerId * 100, ownerId, ownerName, rating, reviews, category, title,
                description, 60, true, Instant.now());
    }

    @Test
    void ranksSameCategoryAboveUnrelatedListings() {
        List<ListingView> candidates = List.of(
                listing(1, "Alice", "Tech", "React basics", "Learn hooks and components", 4.8, 12),
                listing(2, "Bob", "Cooking", "Knife skills", "Chopping and dicing", 4.5, 5));
        List<HelperMatch> matches = HelperMatcher.rank("Tech", "Need help with React state",
                "I want to understand useState and useEffect", candidates, 10);
        assertEquals(1, matches.size(), "only the matching category should score above zero");
        assertEquals(1L, matches.get(0).ownerId());
    }

    @Test
    void keywordOverlapBoostsScoreAndRatedHelpersRankHigher() {
        List<ListingView> candidates = List.of(
                listing(1, "Alice", "Tech", "React basics", "Learn hooks and components", 4.9, 20),
                listing(2, "Carl", "Tech", "Java for beginners", "Collections and generics", 3.0, 1));
        List<HelperMatch> matches = HelperMatcher.rank("Tech", "React hooks question",
                "Struggling with useEffect cleanup in React components", candidates, 10);
        assertTrue(matches.size() >= 2);
        assertEquals(1L, matches.get(0).ownerId(),
                "the listing that mentions the keywords and is highly rated should win");
    }

    @Test
    void keywordsIgnoresShortWordsAndIsCaseInsensitive() {
        var words = HelperMatcher.keywords("I need help with SQL and a big Database migration");
        assertTrue(words.contains("need"));
        assertTrue(words.contains("database"));
        assertTrue(words.contains("migration"));
        assertTrue(words.stream().noneMatch(w -> w.length() < 4),
                "short words like 'sql', 'and', 'big' should be dropped");
    }

    @Test
    void limitIsRespected() {
        List<ListingView> many = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> listing(i + 1, "User" + i, "Tech", "Tech help " + i,
                        "general programming help", 4.0, 3))
                .toList();
        List<HelperMatch> matches = HelperMatcher.rank("Tech", "programming help",
                "general programming help", many, 5);
        assertEquals(5, matches.size());
    }
}
