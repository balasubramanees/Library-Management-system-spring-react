package com.library.books.adapter.out.userservice;

/** user-service could not answer (connection refused, timeout, 5xx). Counted by the circuit breaker. */
public class UserServiceUnavailableException extends RuntimeException {
    public UserServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public UserServiceUnavailableException(String message) {
        super(message);
    }
}
