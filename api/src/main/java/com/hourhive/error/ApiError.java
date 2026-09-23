package com.hourhive.error;

import java.time.Instant;

/**
 * Standard error body for every 4xx/5xx response. {@code error} duplicates {@code message} for
 * older clients that read one or the other; new clients should read {@code message} and {@code code}.
 */
public record ApiError(int status, String code, String error, String message,
                       String path, String requestId, Instant timestamp) {
}
