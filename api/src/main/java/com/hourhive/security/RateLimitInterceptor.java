package com.hourhive.security;

import com.hourhive.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** In-memory fixed-window limiter for login/register (20 per minute per IP). */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final int LIMIT = 20;
    private static final long WINDOW_MS = 60_000;
    private final Map<String, long[]> hits = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            return true;
        }
        String forwarded = req.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim()
                : req.getRemoteAddr();
        long now = System.currentTimeMillis();
        if (hits.size() > 10_000) {
            hits.clear();
        }
        long[] window = hits.compute(ip, (k, cur) -> {
            if (cur == null || now - cur[0] > WINDOW_MS) {
                return new long[] {now, 1};
            }
            cur[1]++;
            return cur;
        });
        if (window[1] > LIMIT) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts, please wait a minute");
        }
        return true;
    }
}
