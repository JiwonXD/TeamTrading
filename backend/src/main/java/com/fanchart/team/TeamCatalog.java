package com.fanchart.team;

import com.fanchart.engine.OrderType;
import com.fanchart.engine.PriceRules;
import com.fanchart.trading.IdempotencyConflictException;
import com.fanchart.trading.TradingException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 구단 목록(teams). 기동할 때 읽어 두고, 운영자가 구단을 등록하면 목록을 새로 바꿔 끼운다.
 * 읽기는 잠금 없이 바꿔 끼운 목록을 보고, 등록은 한 번에 하나씩 한다.
 */
@Component
public class TeamCatalog {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,19}");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final RowMapper<Team> TEAM = (rs, i) -> new Team(rs.getString("id"), rs.getString("name"),
            rs.getString("short_name"), League.valueOf(rs.getString("league")), rs.getLong("start_price"),
            rs.getString("color"), rs.getBoolean("ipo_required"));

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final List<Consumer<Team>> listeners = new ArrayList<>();
    private volatile List<Team> teams;

    TeamCatalog(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.teams = List.copyOf(jdbc.query("SELECT * FROM teams ORDER BY sort_order", TEAM));
    }

    public List<Team> all() {
        return teams;
    }

    public Optional<Team> find(String id) {
        return teams.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /** 구단이 등록되면 불린다. 거래 엔진이 종목을 연다 */
    public synchronized void onRegistered(Consumer<Team> listener) {
        listeners.add(listener);
    }

    /**
     * 리그에 구단을 더한다. 새 구단은 첫 상장 공모 전까지 거래하지 않는다.
     * 첫 기준가는 호가 단위에 맞아야 하고, 공모가는 그 가격제한폭 안에서 정한다.
     */
    public synchronized Team register(String id, String name, String shortName, League league, long startPrice,
                                      String color) {
        if (id == null || !ID.matcher(id).matches())
            throw new TradingException("구단 id는 영문 소문자·숫자·하이픈 2~20자예요");
        if (name == null || name.isBlank() || name.length() > 40) throw new TradingException("구단 이름은 1~40자예요");
        if (shortName == null || shortName.isBlank() || shortName.length() > 10)
            throw new TradingException("짧은 이름은 1~10자예요");
        if (color == null || !COLOR.matcher(color).matches()) throw new TradingException("색은 #rrggbb 형식이에요");
        PriceRules.validate(OrderType.LIMIT, startPrice, 1, startPrice)
                .ifPresent(reason -> {
                    throw new TradingException("첫 기준가: " + reason);
                });
        Team team = new Team(id, name.strip(), shortName.strip(), league, startPrice, color.toLowerCase(), true);
        Integer last = jdbc.queryForObject("SELECT MAX(sort_order) FROM teams", Integer.class);
        try {
            jdbc.update("""
                    INSERT INTO teams (id, name, short_name, league, color, start_price, ipo_required, sort_order, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, ?)""",
                    team.id(), team.name(), team.shortName(), league.name(), team.color(), startPrice,
                    (last == null ? 0 : last) + 1, Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException e) {
            throw new IdempotencyConflictException("이미 있는 구단 id예요");
        }
        List<Team> next = new ArrayList<>(teams);
        next.add(team);
        teams = List.copyOf(next);
        listeners.forEach(l -> l.accept(team));
        return team;
    }
}
