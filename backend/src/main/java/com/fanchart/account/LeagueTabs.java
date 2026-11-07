package com.fanchart.account;

import com.fanchart.team.League;
import com.fanchart.trading.NotFoundException;
import com.fanchart.trading.TradingException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 계정별 홈 리그 탭 순서와 숨긴 리그. 한 번에 통째로 바꾼다.
 * 저장된 뒤 없어진 리그는 읽을 때 건너뛰고, 새로 생긴 리그를 어디에 붙일지는 화면이 정한다.
 */
@Service
public class LeagueTabs {

    public record Prefs(List<League> order, List<League> hidden) {
    }

    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    LeagueTabs(AccountStore accounts, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** 저장한 적이 없으면 순서도 숨김도 비어 있다(화면은 기본 순서로 모두 보인다) */
    public Prefs get(String accountId) {
        Set<String> known = new HashSet<>(Arrays.stream(League.values()).map(League::name).toList());
        List<League> order = new ArrayList<>();
        List<League> hidden = new ArrayList<>();
        jdbc.query("SELECT league, hidden FROM account_league_tabs WHERE account_id = ? ORDER BY position", rs -> {
            String code = rs.getString("league");
            if (!known.contains(code)) return;
            order.add(League.valueOf(code));
            if (rs.getBoolean("hidden")) hidden.add(League.valueOf(code));
        }, accountId);
        return new Prefs(order, hidden);
    }

    public Prefs save(String accountId, List<League> order, List<League> hidden) {
        if (new HashSet<>(order).size() != order.size()) throw new TradingException("같은 리그가 두 번 들어 있어요");
        if (!order.containsAll(hidden)) throw new TradingException("숨길 리그는 순서 목록에도 있어야 해요");
        tx.executeWithoutResult(s -> {
            // 두 기기에서 동시에 저장해도 지우고 넣기가 섞이지 않게 계정 행을 잠근다
            if (!accounts.lockRow(accountId)) throw new NotFoundException("없는 계정이에요");
            jdbc.update("DELETE FROM account_league_tabs WHERE account_id = ?", accountId);
            for (int i = 0; i < order.size(); i++) {
                jdbc.update("INSERT INTO account_league_tabs (account_id, league, position, hidden) VALUES (?, ?, ?, ?)",
                        accountId, order.get(i).name(), i, hidden.contains(order.get(i)));
            }
        });
        return get(accountId);
    }
}
