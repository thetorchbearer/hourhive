package com.hourhive.security;

import com.hourhive.error.ApiException;
import org.springframework.http.HttpStatus;

public final class Auth {
    public static final String ATTR = "uid";

    private Auth() {
    }

    /** Use with {@code @RequestAttribute(value = Auth.ATTR, required = false) Long uid}. */
    public static long require(Long uid) {
        if (uid == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Please sign in first");
        }
        return uid;
    }
}
