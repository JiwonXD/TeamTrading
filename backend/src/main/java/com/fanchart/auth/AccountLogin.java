package com.fanchart.auth;

import com.fanchart.trading.TradingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * 로그인 수단으로 계정을 찾거나 처음이면 만들고, 세션에 로그인 상태를 남긴다.
 * 계정 생성(가입 보상 포함)과 로그인 수단 연결은 한 트랜잭션이라, 같은 사용자의 첫 로그인이 동시에 두 번 와도
 * 기본키 충돌로 한쪽이 롤백되어 계정과 가입 보상은 하나만 남는다.
 */
@Service
public class AccountLogin {

    private final IdentityStore identities;
    private final TradingService trading;
    private final TransactionTemplate tx;
    private final SecurityContextRepository contexts;
    private final AuthProperties props;
    private final Withdrawal withdrawal;
    private final Clock clock;

    AccountLogin(IdentityStore identities, TradingService trading, TransactionTemplate tx,
                 SecurityContextRepository contexts, AuthProperties props, Withdrawal withdrawal, Clock clock) {
        this.identities = identities;
        this.trading = trading;
        this.tx = tx;
        this.contexts = contexts;
        this.props = props;
        this.withdrawal = withdrawal;
        this.clock = clock;
    }

    public String findOrCreate(String provider, String subject) {
        var existing = identities.accountId(provider, subject);
        if (existing.isPresent()) return existing.get();
        withdrawal.checkRejoin(provider, subject);
        try {
            return tx.execute(s -> {
                String id = trading.openAccount().id();
                identities.link(provider, subject, id, clock.instant());
                return id;
            });
        } catch (DuplicateKeyException e) {
            return identities.accountId(provider, subject).orElseThrow(() -> e);
        }
    }

    public enum LinkResult { LINKED, ALREADY_MINE, TAKEN, PROVIDER_IN_USE }

    /**
     * 이미 있는 계정에 소셜 로그인을 더 붙인다(계정 하나에 로그인 수단 여럿). 다른 계정에 연결된 소셜 계정은 붙이지 않고,
     * 같은 제공자의 다른 계정을 하나 더 붙이지도 않는다. 가입 보상은 주지 않는다.
     * 같은 소셜 계정을 두 계정이 동시에 붙이려 해도 (provider, subject) 기본키 때문에 한쪽만 붙는다.
     */
    public LinkResult link(String provider, String subject, String accountId) {
        var existing = identities.accountId(provider, subject);
        if (existing.isPresent()) return existing.get().equals(accountId) ? LinkResult.ALREADY_MINE : LinkResult.TAKEN;
        if (identities.providersOf(accountId).contains(provider)) return LinkResult.PROVIDER_IN_USE;
        try {
            identities.link(provider, subject, accountId, clock.instant());
            return LinkResult.LINKED;
        } catch (DuplicateKeyException e) {
            return identities.accountId(provider, subject).filter(accountId::equals).isPresent()
                    ? LinkResult.ALREADY_MINE : LinkResult.TAKEN;
        }
    }

    /** 로그인 전 세션 id를 버리고(세션 고정 공격 방지) 새 세션에 계정을 남긴다 */
    public void signIn(String accountId, HttpServletRequest req, HttpServletResponse res) {
        if (req.getSession(false) != null) req.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new AccountAuthentication(accountId, props.admins().contains(accountId)));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, req, res);
    }
}
