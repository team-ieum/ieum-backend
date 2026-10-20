package com.ieum.api.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.SlackInstallation;
import com.ieum.auth.domain.SlackUserLink;
import com.ieum.auth.domain.User;
import com.ieum.auth.domain.UserRole;
import com.ieum.auth.repository.RefreshTokenRepository;
import com.ieum.auth.repository.SlackInstallationRepository;
import com.ieum.auth.repository.SlackUserLinkRepository;
import com.ieum.auth.repository.UserRepository;
import com.ieum.workflowcore.document.WorkflowDefinitionRepository;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.repository.WorkflowRepository;
import com.ieum.workflowcore.scheduler.WorkflowScheduler;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 실제 트랜잭션·DB로 탈퇴를 검증한다. mock 단위 테스트로는 못 잡는 문제가 있다 —
 * bulk DELETE의 {@code clearAutomatically}가 아직 flush되지 않은 엔티티 삭제(워크플로우 행, Slack 설치)를
 * 영속성 컨텍스트와 함께 버려 커밋 후에도 행이 남는 일(IEUM-BE-96 머지 전 검토에서 발견).
 */
@SpringBootTest
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class UserServiceWithdrawalIntegrationTest {

    private static final String TEAM_ID = "T-WITHDRAW";

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private SlackInstallationRepository installationRepository;
    @Autowired private SlackUserLinkRepository linkRepository;

    @MockitoBean private RefreshTokenRepository refreshTokenRepository;       // Redis
    @MockitoBean private WorkflowDefinitionRepository definitionRepository;   // Mongo (커밋 후 정리)
    @MockitoBean private WorkflowScheduler workflowScheduler;                 // Quartz (커밋 후 정리)

    private UUID userId;

    @AfterEach
    void clean() {
        if (userId != null) {
            workflowRepository.deleteAll(workflowRepository.findByUserId(userId, Pageable.unpaged()));
            linkRepository.findByUserId(userId).ifPresent(linkRepository::delete);
            userRepository.findById(userId).ifPresent(userRepository::delete);
        }
        installationRepository.findAll().stream()
            .filter(i -> TEAM_ID.equals(i.getTeamId()))
            .forEach(installationRepository::delete);
    }

    @Test
    @DisplayName("워크플로우 여러 개 + 마지막 Slack 사용자 탈퇴 — 워크플로우·Slack 설치까지 커밋 후 남지 않는다")
    void deleteMyAccount_removesEveryWorkflowAndLastSlackInstallation() {
        User user = userRepository.save(User.builder()
            .email("withdraw-it@ieum.test").name("u").provider(AuthProvider.LOCAL).role(UserRole.ROLE_USER).build());
        userId = user.getId();
        for (int i = 1; i <= 3; i++) {
            workflowRepository.save(Workflow.builder().userId(userId).name("wf" + i).isActive(false).build());
        }
        SlackInstallation installation = new SlackInstallation(TEAM_ID);
        // 복호화할 수 없는 값 — 커밋 후 revoke는 건너뛰고(외부 호출 없음) 해제만 검증한다
        installation.applyInstall("not-encrypted", null, null, "chat:write", "팀", "UBOT", "A1");
        installation = installationRepository.save(installation);
        linkRepository.save(new SlackUserLink(userId, installation, "U1"));

        userService.deleteMyAccount(userId);

        assertThat(userRepository.findById(userId)).isEmpty();
        assertThat(workflowRepository.findByUserId(userId, Pageable.unpaged())).isEmpty();
        assertThat(linkRepository.findByUserId(userId)).isEmpty();
        assertThat(installationRepository.findAll()).noneMatch(i -> TEAM_ID.equals(i.getTeamId()));
    }
}
