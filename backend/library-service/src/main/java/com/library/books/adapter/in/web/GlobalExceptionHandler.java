package com.library.books.adapter.in.web;

import com.library.books.adapter.out.userservice.UserServiceUnavailableException;
import com.library.books.domain.LibraryException;
import com.library.commons.web.ApiError;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates exceptions into the shared error envelope.
 * <ul>
 *   <li>Business outcomes ({@link LibraryException}) map to 4xx with user-facing messages.</li>
 *   <li>Dependency failures (user-service down, circuit open) map to 503 + {@code Retry-After}.</li>
 *   <li>Anything else is a bug: logged with stack trace, reported as a generic 500.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Map<String, HttpStatus> STATUS_BY_CODE = Map.of(
            "VALIDATION_ERROR", HttpStatus.BAD_REQUEST,
            "UNAUTHENTICATED", HttpStatus.UNAUTHORIZED,
            "FORBIDDEN", HttpStatus.FORBIDDEN,
            "BOOK_NOT_FOUND", HttpStatus.NOT_FOUND);

    @ExceptionHandler(LibraryException.class)
    ResponseEntity<ApiError> business(LibraryException e) {
        // Everything not listed is a state conflict (already borrowed, not available, ...).
        HttpStatus status = STATUS_BY_CODE.getOrDefault(e.code(), HttpStatus.CONFLICT);
        return ResponseEntity.status(status).body(ApiError.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler({CallNotPermittedException.class, UserServiceUnavailableException.class, RestClientException.class})
    ResponseEntity<ApiError> dependencyDown(RuntimeException e) {
        log.warn("Identity provider unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "10")
                .body(ApiError.of("SERVICE_UNAVAILABLE",
                        "The library is temporarily unavailable. Please try again in a few seconds."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION_ERROR", "Request body is missing or is not valid JSON."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiError.of("UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiError.of("METHOD_NOT_ALLOWED", e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("NOT_FOUND", "Resource not found."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.internalServerError()
                .body(ApiError.of("INTERNAL_ERROR", "Something went wrong. Please try again."));
    }
}
