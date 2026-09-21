package com.hourhive.web;

import com.hourhive.api.Dtos.HelperMatch;
import com.hourhive.api.Dtos.PageResponse;
import com.hourhive.api.Dtos.SkillRequestRequest;
import com.hourhive.api.Dtos.SkillRequestView;
import com.hourhive.security.Auth;
import com.hourhive.service.SkillRequestService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Skill requests board and helper matching. */
@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final SkillRequestService requests;

    public RequestController(SkillRequestService requests) {
        this.requests = requests;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SkillRequestView create(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                                   @Valid @RequestBody SkillRequestRequest req) {
        return requests.create(Auth.require(uid), req);
    }

    @GetMapping
    public PageResponse<SkillRequestView> list(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String sort,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return requests.page(q, category, status, sort, page, size);
    }

    @GetMapping("/mine")
    public List<SkillRequestView> mine(@RequestAttribute(value = Auth.ATTR, required = false) Long uid) {
        return requests.mine(Auth.require(uid));
    }

    @GetMapping("/{id}/matches")
    public List<HelperMatch> matches(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                                     @PathVariable long id) {
        Auth.require(uid);
        return requests.matches(id);
    }

    @PostMapping("/{id}/close")
    public SkillRequestView close(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                                  @PathVariable long id) {
        return requests.close(Auth.require(uid), id);
    }
}
