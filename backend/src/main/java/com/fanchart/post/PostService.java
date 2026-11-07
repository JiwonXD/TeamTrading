package com.fanchart.post;

import com.fanchart.account.AccountStore;
import com.fanchart.points.PointIssuer;
import com.fanchart.points.PointSource;
import com.fanchart.post.Post.ReportReason;
import com.fanchart.post.Post.Reward;
import com.fanchart.post.Post.Status;
import com.fanchart.team.TeamCatalog;
import com.fanchart.trading.NotFoundException;
import com.fanchart.trading.TradingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 종목토론방.
 * <ul>
 * <li>글은 5~300자, 링크는 받지 않는다. 계정마다 20초에 한 번, 하루 30개, 하루 안에 같은 글은 한 번만 쓸 수 있다.
 * 계정 행을 잠그고 확인하므로 동시에 보내도 제한을 넘지 못한다.</li>
 * <li>게시글 보상은 바로 주지 않는다. 글을 쓰고 rewardDelay가 지나도 숨겨지지 않았으면 준다(하루 3번, point_grants 멱등키 post:글 id).
 * 도배하고 바로 지우거나 신고로 숨겨질 글로 포인트를 모으지 못하게 한다.</li>
 * <li>다른 계정 hideAt명이 신고하면 자동으로 숨긴다. 운영자는 숨기거나 되돌릴 수 있다.</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(PostProperties.class)
public class PostService {

    static final int MIN_LENGTH = 5;
    static final int MAX_LENGTH = 300;
    static final Duration MIN_INTERVAL = Duration.ofSeconds(20);
    static final int DAILY_POSTS = 30;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Pattern LINK = Pattern.compile(
            "(?i)(https?://|www\\.|[a-z0-9-]+\\.(com|net|org|kr|io|me|ly|gg|co)(/|\\b))");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Logger log = LoggerFactory.getLogger(PostService.class);

    private final PostStore store;
    private final AccountStore accounts;
    private final TeamCatalog catalog;
    private final PointIssuer points;
    private final TransactionTemplate tx;
    private final PostProperties props;
    private final Clock clock;

    PostService(PostStore store, AccountStore accounts, TeamCatalog catalog, PointIssuer points, TransactionTemplate tx,
                PostProperties props, Clock clock) {
        this.store = store;
        this.accounts = accounts;
        this.catalog = catalog;
        this.points = points;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    public Post write(String accountId, String teamId, String rawBody) {
        catalog.find(teamId).orElseThrow(() -> new NotFoundException("없는 구단이에요"));
        String body = rawBody == null ? "" : SPACES.matcher(rawBody.strip()).replaceAll(" ");
        if (body.codePointCount(0, body.length()) < MIN_LENGTH || body.codePointCount(0, body.length()) > MAX_LENGTH)
            throw new TradingException("글은 " + MIN_LENGTH + "자 이상 " + MAX_LENGTH + "자 이하로 써 주세요");
        if (LINK.matcher(body).find()) throw new TradingException("링크는 올릴 수 없어요");
        return tx.execute(s -> {
            if (!accounts.lockRow(accountId)) throw new NotFoundException("계정을 찾을 수 없어요");
            Instant now = clock.instant();
            var last = store.lastPostAt(accountId);
            if (last.isPresent() && last.get().plus(MIN_INTERVAL).isAfter(now))
                throw new TradingException(MIN_INTERVAL.toSeconds() + "초에 한 번 쓸 수 있어요");
            Instant dayStart = now.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
            if (store.countSince(accountId, dayStart) >= DAILY_POSTS)
                throw new TradingException("오늘은 글을 " + DAILY_POSTS + "개까지 쓸 수 있어요");
            if (store.sameBodySince(accountId, body, now.minus(Duration.ofDays(1))))
                throw new TradingException("같은 글을 다시 올릴 수 없어요");
            boolean holder = accounts.totalShares(accountId, teamId) > 0;
            long id = store.insert(teamId, accountId, body, holder, now);
            return store.find(id).orElseThrow();
        });
    }

    public List<Post> list(String teamId, Long before, int limit) {
        return store.visible(teamId, before, limit);
    }

    /** 작성자 삭제. 보상 전이면 보상도 주지 않는다 */
    public void delete(String accountId, long postId) {
        tx.executeWithoutResult(s -> {
            Post p = store.lock(postId).filter(x -> x.accountId().equals(accountId) && x.status() != Status.DELETED)
                    .orElseThrow(() -> new NotFoundException("지울 수 있는 글이 아니에요"));
            store.setStatus(p.id(), Status.DELETED, clock.instant());
            if (p.reward() == Reward.PENDING) store.setReward(p.id(), Reward.NONE);
        });
    }

    /** 신고. 같은 글은 계정당 한 번이고 다시 보내도 오류가 아니다. 다른 계정 hideAt명이 신고하면 숨긴다 */
    public void report(String accountId, long postId, ReportReason reason) {
        tx.executeWithoutResult(s -> {
            Post p = store.lock(postId).filter(x -> x.status() == Status.VISIBLE)
                    .orElseThrow(() -> new NotFoundException("없는 글이에요"));
            if (p.accountId().equals(accountId)) throw new TradingException("내 글은 신고할 수 없어요");
            if (store.report(postId, accountId, reason, clock.instant()) && p.reports() + 1 >= props.hideAt()) {
                store.setStatus(postId, Status.HIDDEN, clock.instant());
                log.info("post {} hidden after {} reports", postId, p.reports() + 1);
            }
        });
    }

    /** 운영자 숨김 */
    public Post hide(long postId) {
        return moderate(postId, Status.VISIBLE, Status.HIDDEN);
    }

    /** 운영자 복구. 보상은 다시 계산하지 않는다(대기 중이었다면 대기 시간이 지난 뒤 준다) */
    public Post restore(long postId) {
        return moderate(postId, Status.HIDDEN, Status.VISIBLE);
    }

    public List<Post> byStatus(Status status, int limit) {
        return store.byStatus(status, limit);
    }

    /**
     * 대기 시간이 지난 글의 보상. 숨겨졌거나 지운 글은 주지 않고, 하루 한도를 넘었으면 주지 않는다.
     * 글 행을 잠그고 PENDING인지 확인한 뒤 지급하며, 지급 기록의 멱등키가 마지막 방어선이다
     */
    public int payRewards() {
        int paid = 0;
        for (long id : store.rewardDue(clock.instant().minus(props.rewardDelay()))) {
            try {
                Boolean ok = tx.execute(s -> {
                    Post p = store.lock(id).orElseThrow();
                    if (p.reward() != Reward.PENDING) return false;
                    if (p.status() != Status.VISIBLE) {
                        store.setReward(id, Reward.NONE);
                        return false;
                    }
                    boolean granted = points.grantWithinLimit(p.accountId(), PointSource.POST, "post:" + id).isPresent();
                    store.setReward(id, granted ? Reward.PAID : Reward.NONE);
                    return granted;
                });
                if (Boolean.TRUE.equals(ok)) paid++;
            } catch (RuntimeException e) {
                log.error("post reward failed for {}", id, e);
            }
        }
        return paid;
    }

    private Post moderate(long postId, Status from, Status to) {
        return tx.execute(s -> {
            Post p = store.lock(postId).filter(x -> x.status() == from)
                    .orElseThrow(() -> new NotFoundException("그 상태의 글이 아니에요"));
            store.setStatus(p.id(), to, clock.instant());
            return store.find(p.id()).orElseThrow();
        });
    }
}
