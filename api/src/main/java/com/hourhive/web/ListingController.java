package com.hourhive.web;

import com.hourhive.api.Dtos.ListingRequest;
import com.hourhive.api.Dtos.ListingView;
import com.hourhive.security.Auth;
import com.hourhive.service.ListingService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/listings")
public class ListingController {

    private final ListingService listings;

    public ListingController(ListingService listings) {
        this.listings = listings;
    }

    @GetMapping
    public List<ListingView> search(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) String category) {
        return listings.search(q, category);
    }

    @GetMapping("/mine")
    public List<ListingView> mine(@RequestAttribute(value = Auth.ATTR, required = false) Long uid) {
        return listings.mine(Auth.require(uid));
    }

    @GetMapping("/{id}")
    public ListingView get(@PathVariable long id) {
        return listings.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ListingView create(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                              @Valid @RequestBody ListingRequest req) {
        return listings.create(Auth.require(uid), req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                       @PathVariable long id) {
        listings.deactivate(Auth.require(uid), id);
    }
}
