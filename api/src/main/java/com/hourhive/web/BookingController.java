package com.hourhive.web;

import com.hourhive.api.Dtos.BookingRequest;
import com.hourhive.api.Dtos.BookingView;
import com.hourhive.api.Dtos.RescheduleRequest;
import com.hourhive.api.Dtos.ReviewRequest;
import com.hourhive.security.Auth;
import com.hourhive.service.BookingService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookings;

    public BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingView request(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                               @Valid @RequestBody BookingRequest req) {
        return bookings.request(Auth.require(uid), req);
    }

    @GetMapping("/mine")
    public List<BookingView> mine(@RequestAttribute(value = Auth.ATTR, required = false) Long uid) {
        return bookings.mine(Auth.require(uid));
    }

    @PostMapping("/{id}/accept")
    public BookingView accept(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                              @PathVariable long id) {
        return bookings.accept(Auth.require(uid), id);
    }

    @PostMapping("/{id}/decline")
    public BookingView decline(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                               @PathVariable long id) {
        return bookings.decline(Auth.require(uid), id);
    }

    @PostMapping("/{id}/cancel")
    public BookingView cancel(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                              @PathVariable long id) {
        return bookings.cancel(Auth.require(uid), id);
    }

    @PostMapping("/{id}/complete")
    public BookingView complete(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                                @PathVariable long id) {
        return bookings.complete(Auth.require(uid), id);
    }

    @PostMapping("/{id}/reschedule")
    public BookingView reschedule(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                                  @PathVariable long id,
                                  @Valid @RequestBody RescheduleRequest req) {
        return bookings.reschedule(Auth.require(uid), id, req.scheduledAt());
    }

    @PostMapping("/{id}/review")
    @ResponseStatus(HttpStatus.CREATED)
    public void review(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                       @PathVariable long id,
                       @Valid @RequestBody ReviewRequest req) {
        bookings.review(Auth.require(uid), id, req);
    }
}
