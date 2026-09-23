package com.hourhive.web;

import com.hourhive.api.Dtos.ListingRequest;
import com.hourhive.api.Dtos.PageResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
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
    public PageResponse<ListingView> search(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) String category,
                                            @RequestParam(required = false) String sort,
                                            @RequestParam(required = false) Integer minMinutes,
                                            @RequestParam(required = false) Integer maxMinutes,
                                            @RequestParam(required = false) Double minRating,
                                            @RequestParam(required = false) Integer availableDay,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "24") int size) {
        return listings.search(new ListingService.SearchQuery(
                q, category, sort, minMinutes, maxMinutes, minRating, availableDay, page, size));
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

    @PutMapping("/{id}")
    public ListingView update(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                              @PathVariable long id,
                              @Valid @RequestBody ListingRequest req) {
        return listings.update(Auth.require(uid), id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                       @PathVariable long id) {
        listings.deactivate(Auth.require(uid), id);
    }
}
