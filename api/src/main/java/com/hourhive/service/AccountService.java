package com.hourhive.service;

import com.hourhive.api.Dtos.AuthResponse;
import com.hourhive.api.Dtos.LoginRequest;
import com.hourhive.api.Dtos.ProfileRequest;
import com.hourhive.api.Dtos.RegisterRequest;
import com.hourhive.api.Dtos.UserView;
import com.hourhive.config.AppProperties;
import com.hourhive.error.ApiException;
import com.hourhive.security.TokenService;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final LedgerService ledger;
    private final AppProperties props;

    public AccountService(JdbcClient jdbc, PasswordEncoder encoder, TokenService tokens,
                          LedgerService ledger, AppProperties props) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.tokens = tokens;
        this.ledger = ledger;
        this.props = props;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        Long existing = jdbc.sql("select count(*) from users where email = :e")
                .param("e", email).query(Long.class).single();
        if (existing > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "That email is already registered");
        }
        long id = jdbc.sql("""
                insert into users (email, display_name, password_hash)
                values (:e, :n, :p) returning id
                """)
                .param("e", email)
                .param("n", req.displayName().trim())
                .param("p", encoder.encode(req.password()))
                .query(Long.class)
                .single();
        ledger.append(id, props.signupBonusMinutes(), "SIGNUP_BONUS", null, "Welcome to the hive");
        return new AuthResponse(tokens.issue(id), view(id));
    }

    public AuthResponse login(LoginRequest req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        record Row(long id, String hash, boolean disabled) {
        }
        Row row = jdbc.sql("select id, password_hash, disabled from users where email = :e")
                .param("e", email)
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("password_hash"), rs.getBoolean("disabled")))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Wrong email or password"));
        if (!encoder.matches(req.password(), row.hash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Wrong email or password");
        }
        if (row.disabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This account has been suspended", "ACCOUNT_SUSPENDED");
        }
        return new AuthResponse(tokens.issue(row.id()), view(row.id()));
    }

    public UserView view(long userId) {
        return jdbc.sql("select id, email, display_name, bio, role from users where id = :id")
                .param("id", userId)
                .query((rs, n) -> new UserView(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getString("bio"),
                        ledger.balance(userId),
                        rs.getString("role")))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
    }

    public UserView updateProfile(long userId, ProfileRequest req) {
        jdbc.sql("update users set display_name = :n, bio = :b where id = :id")
                .param("n", req.displayName().trim())
                .param("b", req.bio())
                .param("id", userId)
                .update();
        return view(userId);
    }
}
