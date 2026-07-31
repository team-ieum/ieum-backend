package com.ieum.api.workflow.queue;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;

/**
 * 실행 잡 큐 소비 컨테이너 빈. 구독 등록과 기동은 {@link ExecutionJobQueueBootstrap}이 한다
 * (컨테이너가 워커를 알면 워커 → 러너 → 큐 → 컨테이너 순환 참조가 생긴다).
 *
 * <p>컨테이너는 스스로 뜨지 않는다({@code isAutoStartup() == false}). 컨슈머 그룹 생성과
 * 고아 잡 회수가 끝난 뒤에만 기동해야 회수분과 신규 잡이 뒤섞이지 않는다.
 */
@Configuration
public class ExecutionJobQueueConfig {

    /** 폴링 1회에 가져올 잡 수. 실제 동시 실행 수는 workflowExecutor 풀이 정한다. */
    private static final int BATCH_SIZE = 10;

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>>
            executionJobListenerContainer(RedisConnectionFactory redisConnectionFactory) {
        return StreamMessageListenerContainer.create(
            redisConnectionFactory,
            StreamMessageListenerContainerOptions.builder()
                .pollTimeout(Duration.ofSeconds(2))
                .batchSize(BATCH_SIZE)
                .build());
    }
}
