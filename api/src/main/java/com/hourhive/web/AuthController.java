package com.hourhive.web;

import com.hourhive.api.Dtos.AuthResponse;
import com.hourhive.api.Dtos.LedgerView;
import com.hourhive.api.Dtos.LoginRequest;
import com.hourhive.api.Dtos.ProfileRequest;
import com.hourhive.api.Dtos.RegisterRequest;
import com.hourhive.api.Dtos.UserView;
import com.hourhive.security.Auth;
import com.hourhive.service.AccountService;
import com.hourhive.service.LedgerService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final AccountService accounts;
    private final LedgerService ledger;

    public AuthController(AccountService accounts, LedgerService ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        return accounts.register(req);
    }

    @PostMapping("/api/auth/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return accounts.login(req);
    }

    @GetMapping("/api/me")
    public UserView me(@RequestAttribute(value = Auth.ATTR, required = false) Long uid) {
        return accounts.view(Auth.require(uid));
    }

    @PutMapping("/api/me")
    public UserView updateMe(@RequestAttribute(value = Auth.ATTR, required = false) Long uid,
                             @Valid @RequestBody ProfileRequest req) {
        return accounts.updateProfile(Auth.require(uid), req);
    }

    @GetMapping("/api/me/ledger")
    public List<LedgerView> ledger(@RequestAttribute(value = Auth.ATTR, required = false) Long uid) {
        return ledger.history(Auth.require(uid));
    }
}
