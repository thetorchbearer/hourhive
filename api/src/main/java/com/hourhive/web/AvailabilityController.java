package com.hourhive.web;

import com.hourhive.api.Dtos.SlotRequest;
import com.hourhive.api.Dtos.SlotView;
import com.hourhive.security.Auth;
import com.hourhive.service.AvailabilityService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AvailabilityController {

    private final AvailabilityService availability;

    public AvailabilityController(AvailabilityService availability) {
        this.availability = availability;
    }

    @GetMapping("/api/users/{id}/availability")
    public List<SlotView> forUser(@PathVariable long id) {
        return availability.get(id);
    }

    @PutMapping("/api/me/availability")
    public List<SlotView> replace(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                                  @RequestBody List<SlotRequest> slots) {
        return availability.replace(Auth.require(uid), slots);
    }
}
