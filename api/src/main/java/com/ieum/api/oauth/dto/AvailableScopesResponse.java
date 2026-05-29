package com.ieum.api.oauth.dto;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

/**
 * 지원 가능한 Google scope 그룹 목록 응답.
 *
 * <pre>
 * {
 *   "scopes": {
 *     "gmail":    ["https://www.googleapis.com/auth/gmail.modify"],
 *     "sheets":   ["https://www.googleapis.com/auth/spreadsheets"],
 *     "drive":    ["https://www.googleapis.com/auth/drive"],
 *     "calendar": ["https://www.googleapis.com/auth/calendar"]
 *   }
 * }
 * </pre>
 */
@Getter
@Builder
public class AvailableScopesResponse {

    private final Map<String, List<String>> scopes;

    public static AvailableScopesResponse from(Map<String, List<String>> scopes) {
        return AvailableScopesResponse.builder()
            .scopes(scopes)
            .build();
    }
}
