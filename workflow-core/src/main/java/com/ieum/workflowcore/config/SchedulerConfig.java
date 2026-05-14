package com.ieum.workflowcore.config;

import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

/**
 * Quartz 스케줄러 설정.
 *
 * <p>Spring Boot의 QuartzAutoConfiguration은 {@code application.yml}의
 * {@code spring.autoconfigure.exclude} 설정으로 명시적으로 비활성화되어 있다.
 *
 * <p>JobStore: RAMJobStore (서버 재시작 시 Job 손실 → {@link com.ieum.workflowcore.scheduler.ScheduleJobRestorer}가 복구).
 * 운영 환경에서 JDBCJobStore로 전환 시 {@code ieum.scheduler.job-store-type=jdbc} 설정.
 */
@Slf4j
@Configuration
public class SchedulerConfig {

    @Bean
    public QuartzJobFactory quartzJobFactory(ApplicationContext applicationContext) {
        QuartzJobFactory factory = new QuartzJobFactory();
        factory.setApplicationContext(applicationContext);
        return factory;
    }

    @Bean
    public SchedulerFactoryBean schedulerFactoryBean(
            QuartzJobFactory jobFactory,
            @Value("${ieum.scheduler.thread-count:10}") int threadCount,
            @Value("${ieum.scheduler.thread-name-prefix:quartz-}") String threadNamePrefix
    ) {
        SchedulerFactoryBean factory = new SchedulerFactoryBean();
        factory.setJobFactory(jobFactory);
        factory.setWaitForJobsToCompleteOnShutdown(true);
        factory.setOverwriteExistingJobs(true);

        log.info("[SchedulerConfig] Quartz 스레드풀 설정 — threadCount: {}, threadNamePrefix: {}",
            threadCount, threadNamePrefix);

        Properties props = new Properties();
        props.setProperty("org.quartz.threadPool.threadCount", String.valueOf(threadCount));
        props.setProperty("org.quartz.threadPool.threadNamePrefix", threadNamePrefix);
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");

        factory.setQuartzProperties(props);
        return factory;
    }
}
