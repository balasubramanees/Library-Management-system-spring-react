package com.library.books.adapter.out.memory;

import com.library.books.application.port.AccountRepository;
import com.library.books.domain.MemberAccount;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Accounts are immutable records, so they can be stored and returned as-is. */
public class InMemoryAccountRepository implements AccountRepository {

    private final ConcurrentMap<String, MemberAccount> accounts = new ConcurrentHashMap<>();

    @Override
    public Optional<MemberAccount> findByMemberId(String memberId) {
        return Optional.ofNullable(accounts.get(memberId));
    }

    @Override
    public List<MemberAccount> findAll() {
        return List.copyOf(accounts.values());
    }

    @Override
    public void save(MemberAccount account) {
        accounts.put(account.memberId(), account);
    }
}
