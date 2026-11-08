package com.fanchart.auth;

import com.fanchart.trading.TradingException;
import com.fanchart.trading.TradingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 아이디·비밀번호 가입과 로그인(ADR 0037).
 * <ul>
 * <li>비밀번호는 bcrypt 해시만 저장한다.</li>
 * <li>같은 아이디로 연속 5번 틀리면 15분 동안 막는다. 시도를 먼저 세고 맞춰 보므로 동시에 쏟아부어도 5번을 넘지 못한다.</li>
 * <li>없는 아이디와 틀린 비밀번호는 같은 문구로 답하고, 없는 아이디도 bcrypt 비교를 한 번 해서 응답 시간으로 구분하지 못하게 한다.</li>
 * <li>비밀번호 재설정은 가입할 때 이메일을 넣은 계정만, 메일 발송이 설정된 서버에서만 된다.</li>
 * </ul>
 */
@Service
public class PasswordLogin {

    public static final String PROVIDER = "password";
    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(15);
    static final Duration RESET_TTL = Duration.ofMinutes(30);
    /** 같은 아이디로 재설정 메일을 다시 보내려면 이만큼 기다려야 한다. 남의 메일함을 메일로 채우지 못하게 */
    static final Duration RESET_INTERVAL = Duration.ofMinutes(1);
    static final String WRONG = "아이디 또는 비밀번호가 맞지 않아요";

    private static final Pattern LOGIN_ID = Pattern.compile("[a-z][a-z0-9]{3,19}");
    private static final Pattern PASSWORD_CHARS = Pattern.compile("[\\x21-\\x7E]+");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Seoul"));
    private static final Logger log = LoggerFactory.getLogger(PasswordLogin.class);

    private final PasswordLoginStore store;
    private final IdentityStore identities;
    private final TradingService trading;
    private final Withdrawal withdrawal;
    private final ResetMailer mailer;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash = encoder.encode("not-a-real-password-0");

    PasswordLogin(PasswordLoginStore store, IdentityStore identities, TradingService trading, Withdrawal withdrawal,
                  ResetMailer mailer, TransactionTemplate tx, Clock clock) {
        this.store = store;
        this.identities = identities;
        this.trading = trading;
        this.withdrawal = withdrawal;
        this.mailer = mailer;
        this.tx = tx;
        this.clock = clock;
    }

    /** 아이디는 대소문자를 가리지 않는다. 소문자로 바꿔 저장·비교한다 */
    static String normalize(String loginId) {
        return loginId == null ? "" : loginId.strip().toLowerCase(Locale.ROOT);
    }

    static Optional<String> loginIdProblem(String loginId) {
        if (!LOGIN_ID.matcher(loginId).matches()) return Optional.of("아이디는 영문 소문자로 시작하는 영문·숫자 4~20자예요");
        return Optional.empty();
    }

    static Optional<String> passwordProblem(String password, String loginId) {
        if (password == null || password.length() < 8 || password.length() > 64)
            return Optional.of("비밀번호는 8~64자로 정해 주세요");
        if (!PASSWORD_CHARS.matcher(password).matches()) return Optional.of("비밀번호에는 영문·숫자·특수문자만 쓸 수 있어요");
        if (password.chars().noneMatch(Character::isLetter) || password.chars().noneMatch(Character::isDigit))
            return Optional.of("비밀번호에 영문과 숫자를 모두 넣어 주세요");
        if (password.toLowerCase(Locale.ROOT).contains(loginId)) return Optional.of("비밀번호에 아이디를 넣을 수 없어요");
        return Optional.empty();
    }

    /** 가입 화면의 아이디 확인. 쓸 수 없으면 그 이유 */
    public Optional<String> unavailableReason(String rawLoginId) {
        String loginId = normalize(rawLoginId);
        Optional<String> problem = loginIdProblem(loginId);
        if (problem.isPresent()) return problem;
        if (store.exists(loginId) || identities.accountId(PROVIDER, loginId).isPresent())
            return Optional.of("이미 쓰고 있는 아이디예요");
        return Optional.empty();
    }

    /** 가입하고 새 계정 id를 돌려준다. 계정·가입 보상·로그인 연결·비밀번호가 한 트랜잭션이다 */
    public String signUp(String rawLoginId, String password, String rawEmail) {
        String loginId = normalize(rawLoginId);
        loginIdProblem(loginId).ifPresent(r -> {
            throw new TradingException(r);
        });
        passwordProblem(password, loginId).ifPresent(r -> {
            throw new TradingException(r);
        });
        String email = rawEmail == null || rawEmail.isBlank() ? null : rawEmail.strip();
        if (email != null && (email.length() > 254 || !EMAIL.matcher(email).matches()))
            throw new TradingException("이메일 주소를 다시 확인해 주세요");
        withdrawal.checkRejoin(PROVIDER, loginId);
        String hash = encoder.encode(password);
        Instant now = clock.instant();
        try {
            return tx.execute(s -> {
                String id = trading.openAccount().id();
                identities.link(PROVIDER, loginId, id, now);
                store.create(loginId, id, hash, email, now);
                return id;
            });
        } catch (DuplicateKeyException e) {
            throw new TradingException("이미 쓰고 있는 아이디예요");
        }
    }

    /** 맞으면 계정 id. 틀리거나 잠겨 있으면 TradingException */
    public String authenticate(String rawLoginId, String password) {
        String loginId = normalize(rawLoginId);
        Instant now = clock.instant();
        int reserved = store.reserveAttempt(loginId, now, MAX_FAILURES, now.plus(LOCK));
        Optional<PasswordLoginStore.Row> row = store.find(loginId);
        if (row.isEmpty()) {
            encoder.matches(password == null ? "" : password, dummyHash);
            throw new TradingException(WRONG);
        }
        if (reserved == 0) throw locked(row.get().lockedUntil());
        if (password == null || !encoder.matches(password, row.get().passwordHash())) {
            // 이번 시도로 잠겼으면 잠겼다고 알려 준다
            Instant lockedUntil = row.get().lockedUntil();
            if (lockedUntil != null && lockedUntil.isAfter(now)) throw locked(lockedUntil);
            throw new TradingException(WRONG);
        }
        store.clearFailures(loginId);
        return row.get().accountId();
    }

    private static TradingException locked(Instant until) {
        return new TradingException("비밀번호를 " + MAX_FAILURES + "번 틀려서 " + HHMM.format(until) + "까지 로그인할 수 없어요");
    }

    public boolean resetAvailable() {
        return mailer.available();
    }

    /**
     * 아이디와 가입할 때 넣은 이메일이 맞으면 재설정 링크를 메일로 보낸다. 맞든 틀리든 같은 답을 하도록 아무것도 돌려주지 않는다
     */
    public void requestReset(String rawLoginId, String rawEmail) {
        if (!mailer.available()) throw new TradingException("지금은 비밀번호를 찾을 수 없어요");
        String loginId = normalize(rawLoginId);
        String email = rawEmail == null ? "" : rawEmail.strip();
        Optional<PasswordLoginStore.Row> row = store.find(loginId);
        if (row.isEmpty() || row.get().email() == null || !row.get().email().equalsIgnoreCase(email)) return;
        Instant now = clock.instant();
        Optional<Instant> last = store.lastTokenAt(loginId);
        if (last.isPresent() && last.get().plus(RESET_INTERVAL).isAfter(now)) return;
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        store.replaceToken(loginId, Withdrawal.sha256(token), now, now.plus(RESET_TTL));
        try {
            mailer.sendReset(row.get().email(), loginId, token);
        } catch (RuntimeException e) {
            log.error("password reset mail failed for {}", loginId, e);
        }
    }

    /** 메일 링크의 토큰으로 새 비밀번호를 정한다. 토큰은 한 번만 쓸 수 있다 */
    public void confirmReset(String token, String password) {
        if (token == null || token.isBlank()) throw new TradingException("링크가 만료됐어요. 비밀번호 찾기를 다시 해 주세요");
        String hash = Withdrawal.sha256(token.strip());
        // 토큰을 쓰기 전에 비밀번호 규칙부터 본다. 규칙에 걸려도 링크를 다시 쓸 수 있게
        Optional<String> loginId = store.findTokenLogin(hash, clock.instant());
        if (loginId.isEmpty()) throw new TradingException("링크가 만료됐어요. 비밀번호 찾기를 다시 해 주세요");
        passwordProblem(password, loginId.get()).ifPresent(r -> {
            throw new TradingException(r);
        });
        String used = store.useToken(hash, clock.instant())
                .orElseThrow(() -> new TradingException("링크가 만료됐어요. 비밀번호 찾기를 다시 해 주세요"));
        store.changePassword(used, encoder.encode(password));
    }
}
