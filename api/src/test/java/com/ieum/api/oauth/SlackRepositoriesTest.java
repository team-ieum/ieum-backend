package com.ieum.api.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.auth.domain.SlackInstallation;
import com.ieum.auth.domain.SlackUserLink;
import com.ieum.auth.repository.SlackInstallationRepository;
import com.ieum.auth.repository.SlackUserLinkRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** 엔티티 @Table(uniqueConstraints)·FK가 create-drop 스키마에 실제로 생기는지 본다(운영 update와 같은 엔티티 정의). */
@SpringBootTest
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class SlackRepositoriesTest {

    @Autowired private SlackInstallationRepository installationRepository;
    @Autowired private SlackUserLinkRepository linkRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    @AfterEach
    void clean() {
        linkRepository.deleteAll();
        installationRepository.deleteAll();
    }

    private SlackInstallation install(String teamId) {
        SlackInstallation installation = new SlackInstallation(teamId);
        installation.applyInstall("enc-bot", null, null, "chat:write", "팀", "UBOT", "A1");
        return installationRepository.saveAndFlush(installation);
    }

    @Test
    @DisplayName("team_id는 유일 — 같은 워크스페이스 설치 행은 하나(D2)")
    void teamIdIsUnique() {
        install("T1");

        assertThatThrownBy(() -> install("T1")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("user_id는 유일 — 사용자당 워크스페이스 하나(D1)")
    void userIdIsUnique() {
        SlackInstallation t1 = install("T1");
        SlackInstallation t2 = install("T2");
        UUID userId = UUID.randomUUID();
        linkRepository.saveAndFlush(new SlackUserLink(userId, t1, "U1"));

        assertThatThrownBy(() -> linkRepository.saveAndFlush(new SlackUserLink(userId, t2, "U1")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("링크가 남은 설치는 지울 수 없다(FK) — 해제는 링크를 먼저 지운다")
    void installationWithLinksCannotBeDeleted() {
        SlackInstallation t1 = install("T1");
        linkRepository.saveAndFlush(new SlackUserLink(UUID.randomUUID(), t1, "U1"));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            installationRepository.deleteById(t1.getId());
            installationRepository.flush();
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("락 조회(team_id·id), 설치별 링크 수, 사용자 링크 조회")
    void lookups() {
        SlackInstallation t1 = install("T1");
        UUID userId = UUID.randomUUID();
        linkRepository.saveAndFlush(new SlackUserLink(userId, t1, "U1"));
        linkRepository.saveAndFlush(new SlackUserLink(UUID.randomUUID(), t1, "U2"));

        transactionTemplate.executeWithoutResult(tx -> {
            assertThat(installationRepository.findByTeamIdForUpdate("T1")).get()
                .extracting(SlackInstallation::getId).isEqualTo(t1.getId());
            assertThat(installationRepository.findByIdForUpdate(t1.getId())).isPresent();
            assertThat(installationRepository.findByTeamIdForUpdate("T9")).isEmpty();
        });
        assertThat(linkRepository.countByInstallation_Id(t1.getId())).isEqualTo(2);
        assertThat(linkRepository.findByUserId(userId)).get()
            .extracting(SlackUserLink::getSlackUserId).isEqualTo("U1");
    }
}
