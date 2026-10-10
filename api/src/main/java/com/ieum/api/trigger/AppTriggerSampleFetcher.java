package com.ieum.api.trigger;

import com.ieum.workflowcore.domain.enums.TriggerType;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 앱 트리거 노드 테스트용 "최근 항목 1건" 조회. 반환 모양은 런타임 트리거 출력과 같아야 한다 — 같은 정규화 함수를 거칠 것.
 * 구현 빈을 추가하면 {@code NodeTestService}가 triggerType으로 골라 쓴다(Gmail: BE-80, GitHub: BE-81).
 */
public interface AppTriggerSampleFetcher {

    TriggerType triggerType();

    /**
     * @param config 노드 config(편집 중 정의 또는 저장된 노드) 원본
     * @return 정규화 출력 1건. 항목이 없으면 empty
     * @throws com.ieum.common.exception.CustomException 미연동·권한 부족·외부 오류 — 메시지가 FAILED 샘플 사유가 된다
     */
    Optional<Map<String, Object>> fetchLatest(UUID userId, Map<String, Object> config);
}
