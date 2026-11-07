package com.fanchart.feed;

import com.fanchart.team.TeamCatalog;
import com.fanchart.trading.IdempotencyConflictException;
import com.fanchart.trading.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 제공처의 팀 번호와 우리 구단을 잇는다(teams.feed_source, feed_team_id). 연결이 없는 팀의 경기는 들이지 않는다 */
@Component
public class FeedTeams {

    private final JdbcTemplate jdbc;
    private final TeamCatalog catalog;

    FeedTeams(JdbcTemplate jdbc, TeamCatalog catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    /** 구단의 연결을 바꾼다. 한 제공처 번호는 한 구단에만 이을 수 있다 */
    public void link(String teamId, String source, String externalId) {
        if (catalog.find(teamId).isEmpty()) throw new NotFoundException("없는 구단이에요: " + teamId);
        try {
            jdbc.update("UPDATE teams SET feed_source = ?, feed_team_id = ? WHERE id = ?", source, externalId, teamId);
        } catch (DuplicateKeyException e) {
            throw new IdempotencyConflictException("이미 다른 구단에 연결된 팀 번호예요: " + source + " " + externalId);
        }
    }

    public Optional<String> teamFor(String source, String externalId) {
        return jdbc.queryForList("SELECT id FROM teams WHERE feed_source = ? AND feed_team_id = ?", String.class,
                source, externalId).stream().findFirst();
    }
}
