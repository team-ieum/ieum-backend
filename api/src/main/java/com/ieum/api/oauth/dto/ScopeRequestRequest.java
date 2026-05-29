package com.ieum.api.oauth.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 추가 scope 권한 요청 body.
 *
 * <pre>
 * {
 *   "scopeGroups": ["gmail", "sheets"]
 * }
 * </pre>
 */
@Getter
@NoArgsConstructor
public class ScopeRequestRequest {

    @NotEmpty(message = "요청할 scope 그룹을 1개 이상 지정해주세요.")
    private List<String> scopeGroups;
}
