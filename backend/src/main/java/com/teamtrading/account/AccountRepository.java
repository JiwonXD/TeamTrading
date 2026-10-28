package com.teamtrading.account;

import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 첫 버전은 메모리 저장소. 영속화는 다음 단계에서 DB로 옮긴다 */
@Repository
public class AccountRepository {

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    public Account save(Account account) {
        accounts.put(account.id(), account);
        return account;
    }

    public Optional<Account> find(String id) {
        return Optional.ofNullable(accounts.get(id));
    }

    public Collection<Account> all() {
        return accounts.values();
    }
}
