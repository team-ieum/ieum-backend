package com.ieum.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.integration.options.GitHubApiReader;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.auth.service.GitHubAppTokenService;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultGitHubBotTokenProviderTest {

    private final GitHubAppTokenService appTokenService = mock(GitHubAppTokenService.class);
    private final GitHubApiReader reader = mock(GitHubApiReader.class);
    private final DefaultGitHubBotTokenProvider provider = new DefaultGitHubBotTokenProvider(appTokenService, reader);
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void enabled() {
        given(appTokenService.enabled()).willReturn(true);
    }

    private void installed(String fullName) {
        given(reader.installedRepos(userId)).willReturn(List.of(new OptionItem("1", fullName)));
    }

    private void push(String owner, String repo, boolean push) throws Exception {
        given(reader.get(userId, List.of("repos", owner, repo), Map.of())).willReturn(new GitHubApiReader.Page(
            new ObjectMapper().readTree("{\"permissions\": {\"push\": " + push + "}}"), false));
    }

    private static void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CustomException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    @Test
    @DisplayName("설치 범위 밖 레포면 INVALID_INPUT — 권한 조회·설치 토큰 없음")
    void 설치범위_밖이면_INVALID_INPUT() {
        installed("o/other");

        assertErrorCode(() -> provider.getBotToken(userId, "o", "r"), ErrorCode.INVALID_INPUT);
        verify(reader, never()).get(any(), any(), any());
        verify(appTokenService, never()).installationToken(any(), any());
    }

    @Test
    @DisplayName("사용자 토큰의 permissions.push가 false면 FORBIDDEN·쓰기 권한 문구")
    void push_false면_FORBIDDEN_문구() throws Exception {
        installed("o/r");
        push("o", "r", false);

        assertThatThrownBy(() -> provider.getBotToken(userId, "o", "r"))
            .isInstanceOfSatisfying(CustomException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                assertThat(e.getMessage()).isEqualTo("이 저장소에 쓰기 권한이 없어 IEUM 봇으로 리뷰할 수 없어요");
            });
        verify(appTokenService, never()).installationToken(any(), any());
    }

    @Test
    @DisplayName("봇이 꺼져 있으면 사용자 API 호출 전에 GITHUB_BOT_NOT_CONFIGURED")
    void 봇_꺼져있으면_GITHUB_BOT_NOT_CONFIGURED() {
        given(appTokenService.enabled()).willReturn(false);

        assertErrorCode(() -> provider.getBotToken(userId, "o", "r"), ErrorCode.GITHUB_BOT_NOT_CONFIGURED);
        verifyNoInteractions(reader);
    }

    @Test
    @DisplayName("설치 범위 안·push true면 installationToken")
    void 통과하면_installationToken() throws Exception {
        installed("o/r");
        push("o", "r", true);
        given(appTokenService.installationToken("o", "r")).willReturn("ghs_bot");

        assertThat(provider.getBotToken(userId, "o", "r")).isEqualTo("ghs_bot");
    }

    @Test
    @DisplayName("[Review Focus 5] Hodoon/GitHubTest와 설치 목록 hodoon/GitHubTest는 같은 레포")
    void owner_repo_대소문자_무시로_설치범위_비교() throws Exception {
        installed("hodoon/GitHubTest");
        push("Hodoon", "GitHubTest", true);
        given(appTokenService.installationToken("Hodoon", "GitHubTest")).willReturn("ghs_bot");

        assertThat(provider.getBotToken(userId, "Hodoon", "GitHubTest")).isEqualTo("ghs_bot");
    }

    @Test
    @DisplayName("owner·repo가 경로 문자([A-Za-z0-9_.-]+)가 아니면 INVALID_INPUT — GitHub 호출 없음")
    void owner나_repo가_경로문자_아니면_INVALID_INPUT() {
        for (String[] bad : new String[][] {{"o/x", "r"}, {"o", "r/../x"}, {"", "r"}, {"o", null}, {"o", "r?a=1"}}) {
            assertErrorCode(() -> provider.getBotToken(userId, bad[0], bad[1]), ErrorCode.INVALID_INPUT);
        }
        verifyNoInteractions(reader);
        verify(appTokenService, never()).installationToken(any(), any());
    }
}
