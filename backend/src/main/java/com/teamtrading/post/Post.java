package com.teamtrading.post;

import java.time.Instant;

/** @param holder 글을 쓸 때 그 구단 주식을 갖고 있었는지 */
public record Post(long id, String teamId, String accountId, String nickname, String body, boolean holder,
                   Status status, int reports, Reward reward, Instant createdAt) {

    public enum Status { VISIBLE, HIDDEN, DELETED }

    public enum Reward { PENDING, PAID, NONE }

    public enum ReportReason { SPAM, ABUSE, AD, OTHER }
}
