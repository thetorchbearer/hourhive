package com.hourhive.service;

import com.hourhive.api.Dtos.HelperMatch;
import com.hourhive.api.Dtos.ListingView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure scoring logic for "who can help with this request?". */
public final class HelperMatcher {

    private HelperMatcher() {
    }

    public static Set<String> keywords(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() >= 4) {
                out.add(w);
            }
        }
        return out;
    }

    public static List<HelperMatch> rank(String category, String title, String description,
                                         List<ListingView> candidates, int limit) {
        Set<String> words = keywords(title + " " + description);
        List<HelperMatch> out = new ArrayList<>();
        for (ListingView l : candidates) {
            int score = 0;
            List<String> reasons = new ArrayList<>();
            if (l.category().equalsIgnoreCase(category.trim())) {
                score += 5;
                reasons.add("same category");
            }
            String haystack = (l.title() + " " + l.description()).toLowerCase(Locale.ROOT);
            List<String> hits = new ArrayList<>();
            for (String w : words) {
                if (haystack.contains(w)) {
                    hits.add(w);
                }
            }
            if (!hits.isEmpty()) {
                score += Math.min(hits.size(), 5) * 2;
                reasons.add("mentions " + String.join(", ", hits.subList(0, Math.min(3, hits.size()))));
            }
            if (score == 0) {
                continue;
            }
            if (l.ownerReviews() > 0 && l.ownerRating() >= 4.0) {
                score += 2;
                reasons.add("highly rated");
            }
            out.add(new HelperMatch(l.id(), l.ownerId(), l.ownerName(), l.ownerRating(),
                    l.ownerReviews(), l.title(), l.category(), l.minutes(), score, String.join(" · ", reasons)));
        }
        out.sort(Comparator.comparingInt(HelperMatch::score).reversed()
                .thenComparing(Comparator.comparingDouble(HelperMatch::ownerRating).reversed()));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }
}
