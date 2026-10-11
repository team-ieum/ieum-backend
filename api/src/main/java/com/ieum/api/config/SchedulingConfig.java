package com.ieum.api.config;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * {@code @Scheduled} 전용 스케줄러(스레드 2개, {@code scheduling-N}). 없으면 {@code @EnableWebSocketMessageBroker}의
 * {@code messageBrokerTaskScheduler}(풀 = vCPU 수) 때문에 Boot 자동 스케줄러가 물러나 그 위에서 돈다 — 1 vCPU면 Gmail 스윕의
 * 동기 호출이 잡 큐 회수·정리 cron·SockJS heartbeat를 붙잡는다. 빈으로 등록하지 않는다 — {@code TaskScheduler} 빈이 둘이 되면
 * 타입 주입이 모호해진다(그래서 종료도 직접 한다).
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer, DisposableBean {

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("scheduling-");
        scheduler.initialize();
        registrar.setTaskScheduler(scheduler);
    }

    @Override
    public void destroy() {
        scheduler.destroy();
    }
}
