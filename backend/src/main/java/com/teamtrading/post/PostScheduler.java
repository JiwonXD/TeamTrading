package com.teamtrading.post;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** 1분마다 대기 시간이 지난 글의 게시글 보상을 준다 */
@Configuration
@ConditionalOnProperty(name = "teamtrading.posts.auto-reward", havingValue = "true", matchIfMissing = true)
class PostScheduler {

    private final PostService posts;

    PostScheduler(PostService posts) {
        this.posts = posts;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    void payRewards() {
        posts.payRewards();
    }
}
