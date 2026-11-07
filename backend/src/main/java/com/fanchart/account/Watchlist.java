package com.fanchart.account;

import com.fanchart.team.TeamCatalog;
import com.fanchart.trading.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 계정별 관심 구단. 목록을 통째로 바꾸지 않고 한 구단씩 넣고 뺀다.
 * 통째로 바꾸면 한 기기에서 넣은 구단을 다른 기기의 오래된 목록이 지워 버릴 수 있다.
 * 같은 구단을 두 번 넣거나 없는 구단을 빼도 결과는 같다(다시 눌러도 안전하다).
 */
@Service
public class Watchlist {

    public record Entries(List<String> teamIds) {
    }

    private final AccountStore accounts;
    private final TeamCatalog teams;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    Watchlist(AccountStore accounts, TeamCatalog teams, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.accounts = accounts;
        this.teams = teams;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** 넣은 순서대로. 목록에서 빠진 구단은 건너뛴다 */
    public Entries get(String accountId) {
        Set<String> known = new HashSet<>(teams.all().stream().map(t -> t.id()).toList());
        List<String> ids = jdbc.queryForList(
                "SELECT team_id FROM account_watchlist WHERE account_id = ? ORDER BY position", String.class, accountId);
        return new Entries(ids.stream().filter(known::contains).toList());
    }

    public Entries add(String accountId, String teamId) {
        if (teams.find(teamId).isEmpty()) throw new NotFoundException("없는 구단이에요");
        tx.executeWithoutResult(s -> {
            // 두 기기에서 동시에 넣어도 순번이 겹치지 않게 계정 행을 잠근다
            if (!accounts.lockRow(accountId)) throw new NotFoundException("없는 계정이에요");
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM account_watchlist WHERE account_id = ? AND team_id = ?",
                    Integer.class, accountId, teamId);
            if (exists != null && exists > 0) return;
            Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(position) + 1, 0) FROM account_watchlist WHERE account_id = ?",
                    Integer.class, accountId);
            jdbc.update("INSERT INTO account_watchlist (account_id, team_id, position) VALUES (?, ?, ?)", accountId, teamId, next);
        });
        return get(accountId);
    }

    public Entries remove(String accountId, String teamId) {
        jdbc.update("DELETE FROM account_watchlist WHERE account_id = ? AND team_id = ?", accountId, teamId);
        return get(accountId);
    }
}
