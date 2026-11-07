package com.fanchart.load;

import com.fanchart.auth.AccountLogin;
import com.fanchart.engine.PriceRules;
import com.fanchart.ledger.Reconciliation;
import com.fanchart.team.Team;
import com.fanchart.team.TeamCatalog;
import com.fanchart.trading.TradingService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 주문 부하 테스트. 일반 테스트에서는 빠지고 {@code ./gradlew loadTest}로만 돈다.
 * <p>
 * 사용자마다 로그인 세션을 하나씩 두고, 정해진 시간 동안 기준가 근처 지정가 주문과 취소를 쉬지 않고 보낸다.
 * 끝나면 지연 분포와 처리량을 남기고, 정합성(대사 일치, 묶인 포인트 = 대기 매수 예치 합, 호가 비교차, PENDING 없음)을 확인한다.
 * 지연은 실행 환경에 따라 달라서 기준을 두지 않고 기록만 한다. 5xx와 연결 오류는 0이어야 한다.
 * <p>
 * 설정: -Pload.users(기본 40), -Pload.seconds(기본 20), -Pload.teams(기본 3).
 * MySQL에서 돌리려면 SPRING_DATASOURCE_URL 등을 함께 준다.
 */
@Tag("load")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoadTest {

    private static final Pattern RESTING = Pattern.compile("\"orderId\":(\\d+).*\"restingQuantity\":([1-9])");

    @LocalServerPort int port;
    @Autowired AccountLogin login;
    @Autowired TradingService trading;
    @Autowired TeamCatalog catalog;
    @Autowired Reconciliation reconciliation;
    @Autowired JdbcTemplate jdbc;

    private final ConcurrentLinkedQueue<long[]> samples = new ConcurrentLinkedQueue<>(); // {나노초, 상태}
    private final AtomicLong cancels = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    @Test
    void ordersUnderLoadKeepTheLedgerBalanced() throws Exception {
        int users = Integer.getInteger("load.users", 40);
        int seconds = Integer.getInteger("load.seconds", 20);
        List<Team> teams = catalog.all().subList(0, Math.min(Integer.getInteger("load.teams", 3), catalog.all().size()));
        String run = Long.toString(System.currentTimeMillis(), 36);

        List<String> nicknames = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            String nickname = "load-" + run + "-" + i;
            String id = login.findOrCreate("dev", nickname);
            for (Team t : teams) trading.grantShares(id, t.id(), 200, t.basePrice());
            nicknames.add(nickname);
        }

        long started = System.nanoTime();
        long deadline = started + Duration.ofSeconds(seconds).toNanos();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < users; i++) {
                String nickname = nicknames.get(i);
                long seed = i;
                pool.submit(() -> {
                    trade(nickname, teams, new Random(seed), deadline);
                    return null;
                });
            }
        }
        double elapsed = (System.nanoTime() - started) / 1e9;

        String summary = summary(users, teams.size(), elapsed);
        System.out.println(summary);
        Path out = Path.of("build/reports/load/summary.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, summary);

        assertThat(errors.get()).as("5xx·연결 오류").isZero();
        assertThat(samples.stream().filter(s -> s[1] == 201).count()).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE status = 'PENDING'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(locked_points), 0) FROM accounts", Long.class))
                .isEqualTo(jdbc.queryForObject(
                        "SELECT COALESCE(SUM(reserve_per_share * remaining), 0) FROM orders WHERE status = 'OPEN' AND side = 'BUY'",
                        Long.class));
        for (Team t : teams) {
            var book = trading.orderBook(t.id(), 1);
            if (!book.asks().isEmpty() && !book.bids().isEmpty())
                assertThat(book.bids().getFirst().price()).isLessThan(book.asks().getFirst().price());
        }
        Reconciliation.Report report = reconciliation.run();
        assertThat(report.mismatches()).isEmpty();
        assertThat(report.unbalancedJournals()).isEmpty();
        assertThat(report.balanced()).isTrue();
    }

    private void trade(String nickname, List<Team> teams, Random random, long deadline) throws Exception {
        CookieManager cookies = new CookieManager();
        HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        send(http, cookies, "GET", "/api/auth/options", null);
        HttpResponse<String> in = send(http, cookies, "POST", "/api/auth/dev-login", "{\"nickname\":\"" + nickname + "\"}");
        if (in.statusCode() != 204) throw new IllegalStateException("login failed: " + in.statusCode() + " " + in.body());

        List<Long> open = new ArrayList<>();
        int n = 0;
        while (System.nanoTime() < deadline) {
            if (!open.isEmpty() && random.nextInt(10) == 0) {
                long id = open.remove(random.nextInt(open.size()));
                int status = timed(() -> send(http, cookies, "DELETE", "/api/orders/" + id, null)).statusCode();
                if (status < 300) cancels.incrementAndGet();
                continue;
            }
            Team t = teams.get(random.nextInt(teams.size()));
            long tick = PriceRules.tickSize(t.basePrice());
            long price = t.basePrice() + (random.nextInt(7) - 3) * tick;
            String side = random.nextBoolean() ? "BUY" : "SELL";
            String body = "{\"clientOrderId\":\"" + nickname + "-" + (n++) + "\",\"teamId\":\"" + t.id()
                    + "\",\"side\":\"" + side + "\",\"type\":\"LIMIT\",\"price\":" + price
                    + ",\"quantity\":" + (1 + random.nextInt(3)) + "}";
            long t0 = System.nanoTime();
            HttpResponse<String> res;
            try {
                res = send(http, cookies, "POST", "/api/orders", body);
            } catch (IOException e) {
                errors.incrementAndGet();
                continue;
            }
            samples.add(new long[]{System.nanoTime() - t0, res.statusCode()});
            if (res.statusCode() >= 500) errors.incrementAndGet();
            if (res.statusCode() == 201) {
                Matcher m = RESTING.matcher(res.body());
                if (m.find()) open.add(Long.parseLong(m.group(1)));
            }
        }
    }

    private interface Call {
        HttpResponse<String> run() throws Exception;
    }

    private HttpResponse<String> timed(Call call) throws Exception {
        try {
            HttpResponse<String> res = call.run();
            if (res.statusCode() >= 500) errors.incrementAndGet();
            return res;
        } catch (IOException e) {
            errors.incrementAndGet();
            throw e;
        }
    }

    private HttpResponse<String> send(HttpClient http, CookieManager cookies, String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", "application/json");
        cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue).findFirst().ifPresent(token -> b.header("X-XSRF-TOKEN", token));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String summary(int users, int teams, double elapsed) {
        long[] all = samples.stream().mapToLong(s -> s[0]).sorted().toArray();
        long accepted = samples.stream().filter(s -> s[1] == 201).count();
        long rejected = samples.stream().filter(s -> s[1] >= 400 && s[1] < 500).count();
        long trades = jdbc.queryForObject("SELECT COUNT(*) FROM trades", Long.class);
        return String.format("""
                        주문 부하 테스트
                        사용자 %d명, 구단 %d개, %.1f초
                        주문 %d건 (접수 %d, 거절 %d), 취소 %d건, 체결 누적 %d건, 5xx·연결 오류 %d건
                        처리량 %.0f 주문/초
                        주문 지연 p50 %.1fms, p95 %.1fms, p99 %.1fms, 최대 %.1fms
                        """, users, teams, elapsed, all.length, accepted, rejected, cancels.get(), trades, errors.get(),
                all.length / elapsed, ms(all, 0.50), ms(all, 0.95), ms(all, 0.99), all.length == 0 ? 0 : all[all.length - 1] / 1e6);
    }

    private static double ms(long[] sorted, double q) {
        if (sorted.length == 0) return 0;
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1)] / 1e6;
    }
}
