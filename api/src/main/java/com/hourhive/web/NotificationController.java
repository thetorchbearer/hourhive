package com.hourhive.web;

import com.hourhive.api.Dtos.NotificationView;
import com.hourhive.api.Dtos.PageResponse;
import com.hourhive.security.Auth;
import com.hourhive.service.NotificationService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/api/notifications")
    public PageResponse<NotificationView> list(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return notifications.page(Auth.require(uid), page, size);
    }

    @GetMapping("/api/notifications/unread-count")
    public Map<String, Integer> unread(@RequestAttribute(value = Auth.ATTR, required = false) Long uid) {
        return Map.of("unread", notifications.unreadCount(Auth.require(uid)));
    }

    @PostMapping("/api/notifications/{id}/read")
    public Map<String, Boolean> read(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                                     @PathVariable long id) {
        notifications.markRead(Auth.require(uid), id);
        return Map.of("ok", true);
    }

    @PostMapping("/api/notifications/read-all")
    public Map<String, Boolean> readAll(@RequestAttribute(value = Auth.ATTR, required = false) Long uid) {
        notifications.markAllRead(Auth.require(uid));
        return Map.of("ok", true);
    }
}
