package com.fanchart.post;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param rewardDelay 글을 쓰고 보상을 주기까지 기다리는 시간. 그 사이 신고로 숨겨지면 주지 않는다
 * @param autoReward  주기적으로 보상을 줄지
 * @param hideAt      이만큼 다른 계정이 신고하면 자동으로 숨긴다
 */
@ConfigurationProperties("fanchart.posts")
public record PostProperties(Duration rewardDelay, Boolean autoReward, Integer hideAt) {

    public PostProperties {
        if (rewardDelay == null) rewardDelay = Duration.ofMinutes(10);
        if (autoReward == null) autoReward = true;
        if (hideAt == null) hideAt = 3;
    }
}
