package com.hourhive.web;

import com.hourhive.api.Dtos.AdminOverview;
import com.hourhive.api.Dtos.AdminUserView;
import com.hourhive.api.Dtos.AuditView;
import com.hourhive.api.Dtos.BootstrapRequest;
import com.hourhive.api.Dtos.FlagRequest;
import com.hourhive.api.Dtos.LedgerReport;
import com.hourhive.api.Dtos.PageResponse;
import com.hourhive.api.Dtos.RoleRequest;
import com.hourhive.domain.Role;
import com.hourhive.error.ApiException;
import com.hourhive.security.Auth;
import com.hourhive.service.AdminService;
import com.hourhive.service.AuditService;
import com.hourhive.service.RoleService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff endpoints. Every call re-checks the caller's role in the database (RBAC). */
@RestController
public class AdminController {

    private final AdminService admin;
    private final RoleService roles;
    private final AuditService audit;

    public AdminController(AdminService admin, RoleService roles, AuditService audit) {
        this.admin = admin;
        this.roles = roles;
        this.audit = audit;
    }

    /** One-time: promote the caller to ADMIN using the ADMIN_BOOTSTRAP_SECRET env var (only while no admin exists). */
    @PostMapping("/api/admin/bootstrap")
    public Map<String, String> bootstrap(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                                         @Valid @RequestBody BootstrapRequest req) {
        long me = Auth.require(uid);
        if (!roles.bootstrap(me, req.secret())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Bootstrap is not available", "FORBIDDEN");
        }
        audit.record(me, "ADMIN_BOOTSTRAPPED", "user", me, null);
        return Map.of("role", Role.ADMIN.name());
    }

    @GetMapping("/api/admin/overview")
    public AdminOverview overview(@RequestAttribute(value = Auth.ATTR, required = false) Long uid) {
        roles.require(Auth.require(uid), Role.MODERATOR);
        return admin.overview();
    }

    @GetMapping("/api/admin/users")
    public PageResponse<AdminUserView> users(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        roles.require(Auth.require(uid), Role.ADMIN);
        return admin.users(q, page, size);
    }

    @PostMapping("/api/admin/users/{id}/role")
    public Map<String, Boolean> role(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                                     @PathVariable long id, @Valid @RequestBody RoleRequest req) {
        long me = Auth.require(uid);
        roles.require(me, Role.ADMIN);
        admin.changeRole(me, id, req.role());
        return Map.of("ok", true);
    }

    @PostMapping("/api/admin/users/{id}/suspend")
    public Map<String, Boolean> suspend(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                                        @PathVariable long id, @RequestBody FlagRequest req) {
        long me = Auth.require(uid);
        roles.require(me, Role.ADMIN);
        admin.setSuspended(me, id, req.value());
        return Map.of("ok", true);
    }

    @GetMapping("/api/admin/ledger/verify")
    public LedgerReport verifyLedger(@RequestAttribute(value = Auth.ATTR, required = false) Long uid) {
        roles.require(Auth.require(uid), Role.ADMIN);
        return admin.verifyLedger();
    }

    @GetMapping("/api/admin/audit")
    public PageResponse<AuditView> audit(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "30") int size) {
        roles.require(Auth.require(uid), Role.ADMIN);
        return admin.audit(action, page, size);
    }
}
