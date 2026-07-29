package com.ieum.workflowcore.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 민감 필드 마스킹 공용 유틸.
 *
 * <p>{@code node_runs}(SyncExecutionRuntime의 노드 입출력)와 {@code workflow_runs.trigger_data}
 * (WorkflowExecutionService) 저장 전 공통으로 사용한다 — 규칙이 두 곳으로 갈라지면 안 된다.
 * 최상위 키만 검사한다(중첩 Map 내부는 마스킹하지 않음).
 */
public final class SensitiveDataMasker {

    private static final Set<String> SENSITIVE_KEYS =
        Set.of("apiKey", "api_key", "token", "secret", "password", "Authorization");

    private SensitiveDataMasker() {
    }

    public static Map<String, Object> mask(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        Map<String, Object> masked = new LinkedHashMap<>();
        data.forEach((k, v) -> {
            if (SENSITIVE_KEYS.stream().anyMatch(s -> k.toLowerCase().contains(s.toLowerCase()))) {
                masked.put(k, "***");
            } else {
                masked.put(k, v);
            }
        });
        return masked;
    }
}
