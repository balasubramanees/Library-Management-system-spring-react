package com.library.books.domain;

/**
 * Base class for expected business outcomes ("book not available", ...). Each subtype carries a
 * stable {@link #code()}; messages follow the wording of the problem statement.
 */
public abstract class LibraryException extends RuntimeException {

    protected LibraryException(String message) {
        super(message);
    }

    public abstract String code();
}
