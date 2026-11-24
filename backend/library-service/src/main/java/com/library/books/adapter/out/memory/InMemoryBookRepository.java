package com.library.books.adapter.out.memory;

import com.library.books.application.port.BookRepository;
import com.library.books.domain.Book;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Process-local storage, so every start is a clean slate. Books are stored as immutable snapshots
 * and rehydrated on read: callers cannot change stored state without {@link #save}, just like with
 * a real database.
 */
public class InMemoryBookRepository implements BookRepository {

    /** Keyed by title key; insertion order gives the registration order used by {@code list}. */
    private final Map<String, Book.Snapshot> byTitleKey = new LinkedHashMap<>();

    @Override
    public synchronized Optional<Book> findByTitleKey(String titleKey) {
        return Optional.ofNullable(byTitleKey.get(titleKey)).map(Book::restore);
    }

    @Override
    public synchronized List<Book> findAll() {
        return byTitleKey.values().stream().map(Book::restore).toList();
    }

    @Override
    public synchronized void save(Book book) {
        Book.Snapshot snapshot = book.snapshot();
        byTitleKey.put(snapshot.titleKey(), snapshot);
    }
}
