package com.hourhive.security;

import com.hourhive.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Reads the bearer token if present. Endpoints that need a user call {@link Auth#require(Long)}.
 * Suspended accounts may still read, but every write (POST/PUT/DELETE) is refused.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenService tokens;
    private final JdbcClient jdbc;

    public AuthInterceptor(TokenService tokens, JdbcClient jdbc) {
        this.tokens = tokens;
        this.jdbc = jdbc;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            tokens.verify(header.substring(7).trim())
                    .ifPresent(id -> req.setAttribute(Auth.ATTR, id));
        }
        Object uid = req.getAttribute(Auth.ATTR);
        String method = req.getMethod();
        boolean write = method.equals("POST") || method.equals("PUT") || method.equals("DELETE");
        if (uid != null && write && !req.getRequestURI().startsWith("/api/auth/")) {
            Boolean disabled = jdbc.sql("select disabled from users where id = :id")
                    .param("id", uid)
                    .query(Boolean.class)
                    .optional()
                    .orElse(null);
            if (disabled != null && disabled) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Your account has been suspended", "ACCOUNT_SUSPENDED");
            }
        }
        return true;
    }
}
