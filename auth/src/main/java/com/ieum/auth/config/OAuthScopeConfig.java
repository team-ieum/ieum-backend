package com.ieum.auth.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Google OAuth Scope 설정.
 *
 * <p>{@code application.yml}의 {@code google.oauth} 설정을 읽어 scope 목록을 제공한다.
 *
 * <h3>Scope 그룹</h3>
 * <ul>
 *   <li>{@code gmail} — Gmail 읽기/수정</li>
 *   <li>{@code sheets} — Google Sheets 읽기/쓰기</li>
 *   <li>{@code drive} — Google Drive 파일 접근</li>
 *   <li>{@code calendar} — Google Calendar 읽기/쓰기</li>
 * </ul>
 *
 * <h3>Incremental Authorization</h3>
 * {@code google.oauth.incremental-auth=true}이면 증분 승인을 사용한다.
 * 사용자가 이미 승인한 scope는 재승인 없이 유지되고, 새 scope만 동의 화면에 표시된다.
 */
@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "google.oauth")
public class OAuthScopeConfig {

    /** 증분 승인(incremental authorization) 활성 여부. 기본값 true. */
    private boolean incrementalAuth = true;

    /** scope 그룹 맵. key = 그룹명(gmail, sheets, drive, calendar), value = scope URL 목록. */
    private Map<String, List<String>> scopes = new LinkedHashMap<>();

    /**
     * 특정 그룹의 scope 목록을 반환한다.
     *
     * @param group scope 그룹명 (예: "gmail", "sheets")
     * @return scope URL 목록. 그룹이 없으면 빈 리스트.
     */
    public List<String> getScopesByGroup(String group) {
        return scopes.getOrDefault(group.toLowerCase(), Collections.emptyList());
    }

    /**
     * 모든 scope를 단일 리스트로 반환한다.
     *
     * @return 전체 scope URL 목록
     */
    public List<String> getAllScopes() {
        List<String> all = new ArrayList<>();
        scopes.values().forEach(all::addAll);
        return Collections.unmodifiableList(all);
    }

    /**
     * 지원하는 scope 그룹명 목록을 반환한다.
     *
     * @return 그룹명 집합 (예: [gmail, sheets, drive, calendar])
     */
    public java.util.Set<String> getScopeGroups() {
        return Collections.unmodifiableSet(scopes.keySet());
    }
}
