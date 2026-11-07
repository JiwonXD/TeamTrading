package com.fanchart;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 일 마감, 배당 지급 같은 주기 작업을 켠다 */
@Configuration
@EnableScheduling
class SchedulingConfig {
}
