package com.library.commons.web;

/**
 * Error envelope shared by every service: {@code {"error": {"code", "message", "requestId"}}}.
 * The {@code code} is a stable, machine-readable identifier; {@code message} is safe to show to users.
 */
public record ApiError(Body error) {

    public record Body(String code, String message, String requestId) {}

    public static ApiError of(String code, String message) {
        return new ApiError(new Body(code, message, RequestIdFilter.currentRequestId()));
    }
}
