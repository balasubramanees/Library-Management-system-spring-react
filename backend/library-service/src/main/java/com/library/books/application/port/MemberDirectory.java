package com.library.books.application.port;

import com.library.books.domain.Member;
import java.util.Optional;

/**
 * Driven port to the identity provider (user-service). Returns empty for an unknown or expired
 * token; throws if the provider itself cannot be reached.
 */
public interface MemberDirectory {
    Optional<Member> findByToken(String bearerToken);
}
