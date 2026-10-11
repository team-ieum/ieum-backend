package com.ieum.api.integration.options;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * GitHub 트리거의 {@code repoId} 선택지 — App 설치 범위 레포 전체({@link GitHubApiReader#installedRepos}). 값은 숫자 repo id
 * (이름 변경·이전에도 안 변하는 라우팅 키), 라벨은 {@code owner/name}. 트리거 저장·수신 검증과 같은 함수라 "목록엔 없는데 저장은 됨"이 없다.
 *
 * <p>{@code github.repos}(입력 owner, 값=이름)와 다르다 — 그쪽은 액션 노드가 쓴다. 두 단계 조회를 cursor로 쪼개지 않고
 * 한 번에 준다(nextCursor 항상 null, cursor 무시).
 */
@Component
@RequiredArgsConstructor
public class GitHubInstalledReposOptionSource implements OptionSource {

    private final GitHubApiReader gitHubApiReader;

    @Override
    public String key() {
        return "github.installed_repos";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        return new OptionPage(gitHubApiReader.installedRepos(userId), null);
    }
}
