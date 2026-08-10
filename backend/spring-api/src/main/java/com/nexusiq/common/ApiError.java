package com.nexusiq.common;

import java.time.Instant;
import java.util.List;

/** The standard error envelope. Every error response uses this shape (docs/API/API_DESIGN.md). */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String requestId,
        List<FieldDetail> details) {

    public record FieldDetail(String field, String issue) {}

    public static ApiError of(int status, String error, String message, String path, String requestId) {
        return new ApiError(Instant.now(), status, error, message, path, requestId, null);
    }

    public static ApiError of(
            int status, String error, String message, String path, String requestId, List<FieldDetail> details) {
        return new ApiError(Instant.now(), status, error, message, path, requestId, details);
    }
}
