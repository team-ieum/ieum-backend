package com.ieum.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.api.config.DefaultBetaPlatformProvider;
import com.ieum.api.config.DefaultDiscordAccessProvider;
import com.ieum.api.config.DefaultExecutionJobEnqueuer;
import com.ieum.api.config.DefaultSlackTokenProvider;
import com.ieum.api.trigger.github.GitHubIssueSource;
import com.ieum.api.trigger.gmail.GmailTriggerClient;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.executor.BetaPlatformProvider;
import com.ieum.workflowcore.engine.executor.DiscordAccessProvider;
import com.ieum.workflowcore.engine.executor.ExecutionJobEnqueuer;
import com.ieum.workflowcore.engine.executor.HttpNodeExecutor;
import com.ieum.workflowcore.engine.executor.SlackTokenProvider;
import com.ieum.workflowcore.trigger.TriggerHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
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
    private TriggerHandlerRegistry triggerHandlers;

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
    @DisplayName("SlackTokenProvider는 DefaultSlackTokenProvider로 풀린다 (Stub이면 모든 Slack 노드가 미연결로 실패)")
    void slackTokenProvider_resolvesToDefault(@Autowired SlackTokenProvider slackTokenProvider) {
        assertThat(slackTokenProvider).isInstanceOf(DefaultSlackTokenProvider.class);
    }

    @Test
    @DisplayName("DiscordAccessProvider는 DefaultDiscordAccessProvider로 풀린다 (Stub이면 모든 Discord 노드가 미연결로 실패)")
    void discordAccessProvider_resolvesToDefault(@Autowired DiscordAccessProvider discordAccessProvider) {
        assertThat(discordAccessProvider).isInstanceOf(DefaultDiscordAccessProvider.class);
    }

    @Test
    @DisplayName("앱 트리거 핸들러는 api 실구현으로 풀린다 — GITHUB_NEW_ISSUE는 GitHubIssueSource(아니면 저장 접근 검증이 빠져 IDOR), GMAIL_NEW_EMAIL은 GmailTriggerClient")
    void appTriggerHandlers_resolveToApiImplementations() {
        assertThat(triggerHandlers.get(TriggerType.GITHUB_NEW_ISSUE)).isInstanceOf(GitHubIssueSource.class);
        assertThat(triggerHandlers.get(TriggerType.GMAIL_NEW_EMAIL)).isInstanceOf(GmailTriggerClient.class);
    }

    @Test
    @DisplayName("트리거 핸들러 레지스트리가 TriggerType 값을 전부 덮는다 (빠지면 그 트리거가 실행·구독·노드 테스트에서 IllegalStateException)")
    void triggerHandlerRegistry_coversEveryTriggerType(@Autowired TriggerHandlerRegistry registry) {
        assertThat(registry.types()).containsExactlyInAnyOrder(TriggerType.values());
    }

    @Test
    @DisplayName("HttpNodeExecutor는 httpNodeRestTemplate을 받는다 (공용 @Primary면 302를 따라가 SSRF)")
    void httpNodeExecutor_usesHttpNodeRestTemplate(@Autowired ApplicationContext context,
                                                   @Autowired HttpNodeExecutor httpNodeExecutor) {
        assertThat(ReflectionTestUtils.getField(httpNodeExecutor, "restTemplate"))
            .isSameAs(context.getBean("httpNodeRestTemplate"));
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
