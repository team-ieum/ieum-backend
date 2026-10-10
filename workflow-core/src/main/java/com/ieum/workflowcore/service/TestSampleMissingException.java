package com.ieum.workflowcore.service;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.List;
import lombok.Getter;

/**
 * 노드가 직접 참조하는 앞 노드 중 SUCCESS 샘플이 없는 것이 있다 — 실행하지 않고 400으로 돌려준다.
 * FE가 {@code missingNodeIds}를 "먼저 테스트할 노드"로 안내한다. 메시지엔 노드 id만 싣는다.
 */
@Getter
public class TestSampleMissingException extends CustomException {

    private final List<String> missingNodeIds;

    public TestSampleMissingException(List<String> missingNodeIds) {
        super(ErrorCode.TEST_SAMPLE_MISSING,
            "먼저 테스트해야 하는 노드가 있습니다: " + String.join(", ", missingNodeIds));
        this.missingNodeIds = List.copyOf(missingNodeIds);
    }
}
