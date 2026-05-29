package com.ieum.api.oauth.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 현재 사용자가 보유한 Google scope 목록 응답.
 */
@Getter
@Builder
public class MyScopesResponse {

    /** 현재 보유 scope URL 목록. Google 미연동 시 빈 리스트. */
    private final List<String> scopes;

    /** Google 계정 연동 여부. */
    private final boolean connected;

    public static MyScopesResponse of(List<String> scopes) {
        return MyScopesResponse.builder()
            .scopes(scopes)
            .connected(!scopes.isEmpty())
            .build();
    }
}
