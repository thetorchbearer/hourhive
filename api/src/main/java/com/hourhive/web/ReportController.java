package com.hourhive.web;

import com.hourhive.api.Dtos.PageResponse;
import com.hourhive.api.Dtos.ReportRequest;
import com.hourhive.api.Dtos.ReportView;
import com.hourhive.api.Dtos.ResolveReportRequest;
import com.hourhive.domain.Role;
import com.hourhive.security.Auth;
import com.hourhive.service.ReportService;
import com.hourhive.service.RoleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportController {

    private final ReportService reports;
    private final RoleService roles;

    public ReportController(ReportService reports, RoleService roles) {
        this.reports = reports;
        this.roles = roles;
    }

    @PostMapping("/api/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportView create(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                             @Valid @RequestBody ReportRequest req) {
        return reports.create(Auth.require(uid), req);
    }

    @GetMapping("/api/admin/reports")
    public PageResponse<ReportView> list(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        roles.require(Auth.require(uid), Role.MODERATOR);
        return reports.page(status, page, size);
    }

    @PostMapping("/api/admin/reports/{id}/resolve")
    public ReportView resolve(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                              @PathVariable long id,
                              @Valid @RequestBody ResolveReportRequest req) {
        long me = Auth.require(uid);
        roles.require(me, Role.MODERATOR);
        return reports.resolve(me, id, req);
    }
}
