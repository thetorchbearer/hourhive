package com.hourhive.error;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One place that turns every failure into the same {@link ApiError} JSON shape, so the frontend
 * (and any other client) has exactly one error format to handle.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApi(ApiException e, HttpServletRequest req) {
        return build(e.status().value(), e.code(), e.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Object> handleDuplicate(DuplicateKeyException e, HttpServletRequest req) {
        return build(409, "CONFLICT", "That already exists", req.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleOther(Exception e, HttpServletRequest req) {
        log.error("Unhandled error path={}", req.getRequestURI(), e);
        return build(500, "INTERNAL_ERROR", "Something went wrong on our side", req.getRequestURI());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(400, "VALIDATION_FAILED", msg.isEmpty() ? "Invalid request" : msg, path(request));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return build(400, "MALFORMED_BODY", "Malformed request body", path(request));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        HttpStatus hs = HttpStatus.resolve(statusCode.value());
        String code = hs == null ? "ERROR" : hs.name();
        String msg = hs == null ? "Request failed" : hs.getReasonPhrase();
        return build(statusCode.value(), code, msg, path(request));
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
    }

    private static ResponseEntity<Object> build(int status, String code, String message, String path) {
        ApiError body = new ApiError(status, code, message, message, path, MDC.get("requestId"), Instant.now());
        return ResponseEntity.status(status).body(body);
    }
}
