package com.library.books.application.port;

import com.library.books.domain.Book;
import java.util.List;
import java.util.Optional;

/** Driven port: book storage. Implementations must be thread-safe. */
public interface BookRepository {
    Optional<Book> findByTitleKey(String titleKey);

    /** All books in registration order. */
    List<Book> findAll();

    void save(Book book);
}
