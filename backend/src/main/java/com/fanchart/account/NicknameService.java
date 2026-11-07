package com.fanchart.account;

import com.fanchart.trading.NotFoundException;
import com.fanchart.trading.TradingException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * 닉네임 변경. 겹침 검사와 변경 주기 검사를 조건부 UPDATE 한 문장과 고유 인덱스에 맡겨,
 * 두 사람이 같은 닉네임을 동시에 골라도 한 사람만 갖고, 같은 계정이 동시에 두 번 바꿔도 한 번만 바뀐다.
 */
@Service
public class NicknameService {

    private final AccountStore accounts;
    private final Clock clock;

    NicknameService(AccountStore accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    public Account change(String accountId, String requested) {
        String nickname = requested.strip();
        Nicknames.problem(nickname).ifPresent(reason -> {
            throw new TradingException(reason);
        });
        Account current = accounts.find(accountId).orElseThrow(() -> new NotFoundException("계정을 찾을 수 없어요"));
        if (current.nickname().equals(nickname)) throw new TradingException("지금 쓰고 있는 닉네임이에요");
        Instant now = clock.instant();
        boolean changed;
        try {
            changed = accounts.changeNickname(accountId, nickname, now, now.minus(Nicknames.COOLDOWN));
        } catch (DuplicateKeyException e) {
            throw new TradingException("다른 사람이 쓰고 있는 닉네임이에요");
        }
        if (!changed) throw new TradingException("닉네임은 " + Nicknames.COOLDOWN.toDays() + "일에 한 번 바꿀 수 있어요");
        return accounts.find(accountId).orElseThrow();
    }

    /** 다음에 바꿀 수 있는 시각. 지금 바꿀 수 있으면 null */
    public Instant changeableAt(Account account) {
        if (account.nicknameChangedAt() == null) return null;
        Instant at = account.nicknameChangedAt().plus(Nicknames.COOLDOWN);
        return at.isAfter(clock.instant()) ? at : null;
    }
}
