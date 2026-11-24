package com.library.books.application.port;

import com.library.books.domain.MemberAccount;
import java.util.List;
import java.util.Optional;

/** Driven port: members' deposit accounts. Implementations must be thread-safe. */
public interface AccountRepository {
    Optional<MemberAccount> findByMemberId(String memberId);

    /** Every registered member's account (a member registers on their first login). */
    List<MemberAccount> findAll();

    void save(MemberAccount account);
}
