package com.ieum.workflowcore.service;

import com.ieum.workflowcore.domain.enums.SampleStatus;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 노드 테스트 한 번(또는 저장된 최신 샘플)의 결과. {@code output}은 마스킹된 값이고 FAILED면 null,
 * {@code error}는 FAILED일 때만 채워진다. {@code detail}은 CONDITION 테스트 직후 응답에만(마스킹됨) 실리고,
 * 저장된 샘플에서 읽으면 null이다.
 */
public record NodeTestResult(SampleStatus status, Map<String, Object> output, String error,
                             LocalDateTime testedAt, Map<String, Object> detail) {

    public NodeTestResult(SampleStatus status, Map<String, Object> output, String error, LocalDateTime testedAt) {
        this(status, output, error, testedAt, null);
    }
}
