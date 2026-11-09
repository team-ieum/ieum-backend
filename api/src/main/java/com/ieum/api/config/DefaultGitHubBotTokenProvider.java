package com.ieum.api.config;

import com.ieum.api.integration.options.GitHubApiReader;
import com.ieum.auth.service.GitHubAppTokenService;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.engine.executor.GitHubBotTokenProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 봇 명의 전 사용자 권한 검사(spec §5.3) — 사용자 토큰으로 ① 설치 범위 안(트리거 IDOR 방어와 같은 기준) ② 쓰기 권한
 * ({@code permissions.push})을 확인한 뒤에만 설치 토큰을 받는다. 설치 범위만 보면 읽기 권한뿐인 조직원·공개 레포 방문자도
 * 봇 이름으로 승인·변경 요청을 낼 수 있다.
 */
@Component
@RequiredArgsConstructor
public class DefaultGitHubBotTokenProvider implements GitHubBotTokenProvider {

    static final String NO_WRITE_PERMISSION = "이 저장소에 쓰기 권한이 없어 IEUM 봇으로 리뷰할 수 없어요";
    // api GitHubIssueSource.REPO_NOT_INSTALLED와 같은 글자(패키지 비공개라 복사)
    static final String REPO_NOT_INSTALLED =
        "이 레포에 GitHub 앱 설치가 필요합니다. GitHub App 설치 범위에 저장소를 추가해 주세요.";
    private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");

    private final GitHubAppTokenService gitHubAppTokenService;
    private final GitHubApiReader gitHubApiReader;

    @Override
    public String getBotToken(UUID userId, String owner, String repo) {
        if (!gitHubAppTokenService.enabled()) {
            throw new CustomException(ErrorCode.GITHUB_BOT_NOT_CONFIGURED);
        }
        if (owner == null || repo == null
            || !PATH_SEGMENT.matcher(owner).matches() || !PATH_SEGMENT.matcher(repo).matches()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "GitHub 레포 이름 형식이 올바르지 않습니다.");
        }
        String fullName = owner + "/" + repo;
        // GitHub 이름은 대소문자를 구분하지 않는다 — 참조식 owner(Hodoon)와 설치 목록(hodoon)이 다를 수 있다
        if (gitHubApiReader.installedRepos(userId).stream().noneMatch(r -> r.name().equalsIgnoreCase(fullName))) {
            throw new CustomException(ErrorCode.INVALID_INPUT, REPO_NOT_INSTALLED);
        }
        boolean push = gitHubApiReader.get(userId, List.of("repos", owner, repo), Map.of())
            .body().path("permissions").path("push").asBoolean(false);
        if (!push) {
            throw new CustomException(ErrorCode.FORBIDDEN, NO_WRITE_PERMISSION);
        }
        return gitHubAppTokenService.installationToken(owner, repo);
    }
}
