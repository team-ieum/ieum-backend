package com.ieum.workflowcore.trigger;

import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.Node;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 트리거 종류 하나의 동작을 모은 포트 — 실행 출력, 구독(app_trigger_subscriptions), 저장 시점 접근 검증, 노드 테스트.
 * 공통 코드(실행기·구독 동기화·노드 테스트)는 {@link TriggerHandlerRegistry}로 핸들러를 찾아 부르기만 하고 종류별로 분기하지 않는다.
 * 새 트리거는 {@link TriggerType} 상수와 이 인터페이스 구현 빈 하나를 추가한다(수신 경로는 그 트리거가 따로 쓴다).
 *
 * <p>추상 메서드는 {@link #type()} 하나다. 나머지 기본값은 "구독 없음·검증 없음·러너로 실행·planted 출력"이다.
 */
public interface TriggerHandler {

    TriggerType type();

    /**
     * 트리거 노드 출력. 부작용 금지(재처리 때 다시 불린다). 실패는 예외 → 실행기가 failure로 바꾼다.
     *
     * @param input   런타임이 치환한 config 복사본(null이면 빈 Map으로 넘어온다)
     * @param planted 런타임·노드 테스트가 트리거 노드 출력 자리에 심은 페이로드(없으면 빈 Map)
     * @return 기본 = planted 복사(WEBHOOK·앱 트리거)
     */
    default Map<String, Object> output(Node node, Map<String, Object> input, Map<String, Object> planted) {
        return new HashMap<>(planted);
    }

    /** app_trigger_subscriptions 행으로 켜지는 트리거인가 */
    default boolean subscribes() {
        return false;
    }

    /** 구독 스냅샷 — {@link #subscribes()}일 때만 불린다. 저장할 키만 남긴다(HashMap — 값이 null일 수 있다) */
    default Map<String, Object> snapshot(Map<?, ?> config) {
        return new HashMap<>();
    }

    /** webhook 라우팅 키(resource_key). 없으면 null */
    default String resourceKey(Map<String, Object> snapshot) {
        return null;
    }

    /** 행을 새로 만들거나 스냅샷이 바뀌었을 때의 폴링 시작 상태 */
    default PollStart pollStart(LocalDateTime now) {
        return PollStart.NONE;
    }

    /**
     * 저장·활성화 시점 접근 검증(IDOR). 저장 트랜잭션 안에서 불리고 거부는 CustomException(저장 전체 롤백).
     * 같은 행·같은 resourceKey 재저장에는 불리지 않는다. 기본 = 검증 없음 — 외부 리소스를 고르는 트리거는 반드시 오버라이드.
     */
    default void verifyAccess(UUID userId, Map<String, Object> snapshot) {
    }

    /** 노드 테스트 방식 */
    default NodeTestMode nodeTestMode() {
        return NodeTestMode.RUN;
    }

    /**
     * FETCH 모드의 최근 1건(정규화 출력 = 런타임 출력과 같은 모양).
     *
     * @param config 노드 config 원본
     * @throws com.ieum.common.exception.CustomException 거부 — 메시지가 FAILED 샘플 사유가 된다
     */
    default Optional<Map<String, Object>> fetchSample(UUID userId, Map<String, Object> config) {
        return Optional.empty();
    }
}
