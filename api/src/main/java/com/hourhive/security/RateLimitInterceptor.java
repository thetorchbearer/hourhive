package com.hourhive.security;

import com.hourhive.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * In-memory fixed-window limiter with three scopes:
 * auth endpoints (20/min per IP, brute-force guard), writes (60/min per signed-in user, or per IP
 * when anonymous), reads (300/min per IP). Runs after {@link AuthInterceptor} so writes can be
 * scoped per user rather than per IP. Responses always carry X-RateLimit-Limit / -Remaining;
 * a 429 also carries Retry-After.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    static final long WINDOW_MS = 60_000;
    private final Map<String, long[]> hits = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String method = req.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        String path = req.getRequestURI();
        if (path.equals("/api/ping") || path.equals("/api/health")) {
            return true;
        }
        String ip = clientIp(req);
        String scope;
        int limit;
        String key;
        if (path.startsWith("/api/auth/")) {
            scope = "auth";
            limit = 20;
            key = ip;
        } else if (method.equals("POST") || method.equals("PUT") || method.equals("DELETE")) {
            scope = "write";
            limit = 60;
            Object uid = req.getAttribute(Auth.ATTR);
            key = uid != null ? "u" + uid : ip;
        } else {
            scope = "read";
            limit = 300;
            key = ip;
        }
        long now = System.currentTimeMillis();
        if (hits.size() > 20_000) {
            hits.clear();
        }
        long[] window = hits.compute(scope + ":" + key, (k, cur) -> {
            if (cur == null || now - cur[0] > WINDOW_MS) {
                return new long[] {now, 1};
            }
            cur[1]++;
            return cur;
        });
        long remaining = Math.max(0, limit - window[1]);
        res.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        res.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        if (window[1] > limit) {
            long retry = Math.max(1, (WINDOW_MS - (now - window[0]) + 999) / 1000);
            res.setHeader("Retry-After", String.valueOf(retry));
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many requests, please retry in " + retry + " seconds", "RATE_LIMITED");
        }
        return true;
    }

    private static String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
    }
}
