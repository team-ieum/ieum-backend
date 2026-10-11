package com.ieum.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.api.config.DefaultBetaPlatformProvider;
import com.ieum.api.config.DefaultExecutionJobEnqueuer;
import com.ieum.api.trigger.github.GitHubIssueSource;
import com.ieum.workflowcore.engine.executor.AppTriggerAccessVerifier;
import com.ieum.workflowcore.engine.executor.BetaPlatformProvider;
import com.ieum.workflowcore.engine.executor.ExecutionJobEnqueuer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class IeumApplicationTest {

    @Autowired
    private BetaPlatformProvider betaPlatformProvider;

    @Autowired
    private ExecutionJobEnqueuer executionJobEnqueuer;

    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduledProcessor;

    @Autowired
    private AppTriggerAccessVerifier appTriggerAccessVerifier;

    @Test
    @DisplayName("스프링 부트 애플리케이션 컨텍스트가 정상적으로 로드된다")
    void contextLoads() {
    }

    @Test
    @DisplayName("BetaPlatformProvider는 단일 빈이며 DefaultBetaPlatformProvider가 항상 선택된다 (Stub에 가려지지 않음)")
    void betaPlatformProvider_singleBean_resolvesToDefault() {
        assertThat(betaPlatformProvider).isInstanceOf(DefaultBetaPlatformProvider.class);
    }

    @Test
    @DisplayName("ExecutionJobEnqueuer는 단일 빈이며 DefaultExecutionJobEnqueuer가 선택된다 (Stub이면 조용히 내구성을 잃는다)")
    void executionJobEnqueuer_singleBean_resolvesToDefault() {
        assertThat(executionJobEnqueuer).isInstanceOf(DefaultExecutionJobEnqueuer.class);
    }

    @Test
    @DisplayName("AppTriggerAccessVerifier는 단일 빈이며 GitHubIssueSource가 선택된다 (Stub이면 저장 검증이 전부 통과 = IDOR)")
    void appTriggerAccessVerifier_singleBean_resolvesToGitHubIssueSource() {
        assertThat(appTriggerAccessVerifier).isInstanceOf(GitHubIssueSource.class);
    }

    @Test
    @DisplayName("@Scheduled는 WebSocket 브로커 스케줄러가 아니라 전용 scheduling- 풀(2)에서 돈다")
    void scheduledTasks_runOnDedicatedScheduler() {
        ScheduledTaskRegistrar registrar =
            (ScheduledTaskRegistrar) ReflectionTestUtils.getField(scheduledProcessor, "registrar");

        assertThat(registrar.getScheduler()).isInstanceOfSatisfying(ThreadPoolTaskScheduler.class, s -> {
            assertThat(s.getThreadNamePrefix()).isEqualTo("scheduling-");
            assertThat(s.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
        });
    }
}
