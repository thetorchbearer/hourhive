package com.hourhive.web;

import com.hourhive.api.Dtos.CommunityStats;
import com.hourhive.api.Dtos.MeSummary;
import com.hourhive.api.Dtos.MessageRequest;
import com.hourhive.api.Dtos.MessageView;
import com.hourhive.api.Dtos.ProfileView;
import com.hourhive.security.Auth;
import com.hourhive.service.BookingService;
import com.hourhive.service.MessageService;
import com.hourhive.service.ProfileService;
import com.hourhive.service.StatsService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Profiles, booking messages, community stats and the signed-in member's activity summary. */
@RestController
public class CommunityController {

    private final ProfileService profiles;
    private final MessageService messages;
    private final StatsService stats;
    private final BookingService bookings;

    public CommunityController(ProfileService profiles, MessageService messages,
                               StatsService stats, BookingService bookings) {
        this.profiles = profiles;
        this.messages = messages;
        this.stats = stats;
        this.bookings = bookings;
    }

    @GetMapping("/api/users/{id}")
    public ProfileView profile(@PathVariable long id) {
        return profiles.get(id);
    }

    @GetMapping("/api/stats")
    public CommunityStats stats() {
        return stats.community();
    }

    @GetMapping("/api/me/summary")
    public MeSummary summary(@RequestAttribute(value = Auth.ATTR, required = false) Long uid) {
        return bookings.summary(Auth.require(uid));
    }

    @GetMapping("/api/bookings/{id}/messages")
    public List<MessageView> messages(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                                      @PathVariable long id) {
        return messages.list(Auth.require(uid), id);
    }

    @PostMapping("/api/bookings/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageView send(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                            @PathVariable long id,
                            @Valid @RequestBody MessageRequest req) {
        return messages.send(Auth.require(uid), id, req);
    }
}
