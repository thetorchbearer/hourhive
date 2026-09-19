package com.hourhive.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Reads the bearer token if present. Endpoints that need a user call {@link Auth#require(Long)}. */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenService tokens;

    public AuthInterceptor(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            tokens.verify(header.substring(7).trim())
                    .ifPresent(id -> req.setAttribute(Auth.ATTR, id));
        }
        return true;
    }
}
