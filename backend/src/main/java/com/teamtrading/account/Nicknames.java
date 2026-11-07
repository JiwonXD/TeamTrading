package com.teamtrading.account;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** 닉네임 규칙. 한 닉네임은 한 계정만 쓴다(DB 고유 인덱스) */
public final class Nicknames {

    /** 한 번 바꾸면 이 기간이 지나야 다시 바꿀 수 있다. 리더보드에서 이름을 자주 바꿔 헷갈리게 하지 못하게 */
    public static final Duration COOLDOWN = Duration.ofDays(7);

    private static final Pattern ALLOWED = Pattern.compile("[가-힣A-Za-z0-9_]{2,12}");
    private static final List<String> RESERVED = List.of("운영", "관리자", "admin", "팀트", "teamtrading");

    private Nicknames() {
    }

    /** 쓸 수 없는 닉네임이면 그 이유 */
    public static Optional<String> problem(String nickname) {
        if (!ALLOWED.matcher(nickname).matches())
            return Optional.of("닉네임은 한글·영문·숫자·밑줄(_)로 2~12자예요");
        String lower = nickname.toLowerCase(Locale.ROOT);
        if (RESERVED.stream().anyMatch(lower::contains)) return Optional.of("운영자로 오해할 수 있는 닉네임은 쓸 수 없어요");
        return Optional.empty();
    }

    /** 가입할 때 붙이는 기본 닉네임. 겹치면 부르는 쪽이 다시 뽑는다 */
    public static String random() {
        return "투자자" + ThreadLocalRandom.current().nextInt(100_000, 1_000_000);
    }
}
