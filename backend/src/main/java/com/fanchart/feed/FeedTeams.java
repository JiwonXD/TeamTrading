package com.fanchart.feed;

import com.fanchart.team.TeamCatalog;
import com.fanchart.trading.IdempotencyConflictException;
import com.fanchart.trading.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 제공처의 팀 번호와 우리 구단을 잇는다(team_feed_ids). 한 구단은 제공처마다 번호를 하나씩 가진다
 * (예: 리그·챔스는 football-data, 국내 컵은 API-Sports). 리그 정규 시즌은 연결이 없는 팀의 경기를 들이지 않는다
 */
@Component
public class FeedTeams {

    private final JdbcTemplate jdbc;
    private final TeamCatalog catalog;

    FeedTeams(JdbcTemplate jdbc, TeamCatalog catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    /** 구단의 이 제공처 연결을 바꾼다. 다른 제공처 연결은 그대로다. 한 제공처 번호는 한 구단에만 이을 수 있다 */
    @Transactional
    public void link(String teamId, String source, String externalId) {
        if (catalog.find(teamId).isEmpty()) throw new NotFoundException("없는 구단이에요: " + teamId);
        String owner = teamFor(source, externalId).orElse(null);
        if (owner != null && !owner.equals(teamId))
            throw new IdempotencyConflictException("이미 다른 구단에 연결된 팀 번호예요: " + source + " " + externalId);
        jdbc.update("DELETE FROM team_feed_ids WHERE team_id = ? AND source = ?", teamId, source);
        try {
            jdbc.update("INSERT INTO team_feed_ids (source, external_id, team_id) VALUES (?, ?, ?)", source,
                    externalId, teamId);
        } catch (DuplicateKeyException e) {
            throw new IdempotencyConflictException("이미 다른 구단에 연결된 팀 번호예요: " + source + " " + externalId);
        }
    }

    public Optional<String> teamFor(String source, String externalId) {
        return jdbc.queryForList("SELECT team_id FROM team_feed_ids WHERE source = ? AND external_id = ?",
                String.class, source, externalId).stream().findFirst();
    }

    /** 구단의 제공처별 팀 번호 */
    public Map<String, String> linksOf(String teamId) {
        Map<String, String> out = new TreeMap<>();
        jdbc.query("SELECT source, external_id FROM team_feed_ids WHERE team_id = ?",
                rs -> {
                    out.put(rs.getString(1), rs.getString(2));
                }, teamId);
        return out;
    }
}
