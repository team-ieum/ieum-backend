package com.ieum.workflowcore.trigger;

import com.ieum.workflowcore.domain.enums.TriggerType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@link TriggerHandler} 빈을 type으로 모은다. 같은 type이 둘이면 기동 실패, 없는 type 조회는 예외(fail-closed —
 * 핸들러 없는 트리거를 MANUAL처럼 흘려보내지 않는다). 전 {@link TriggerType} 커버는 api 기동 검사가 보장한다.
 */
@Component
public class TriggerHandlerRegistry {

    private final Map<TriggerType, TriggerHandler> handlers = new EnumMap<>(TriggerType.class);

    public TriggerHandlerRegistry(List<TriggerHandler> handlers) {
        for (TriggerHandler handler : handlers) {
            if (this.handlers.putIfAbsent(handler.type(), handler) != null) {
                throw new IllegalStateException("트리거 핸들러 중복: " + handler.type());
            }
        }
    }

    public TriggerHandler get(TriggerType type) {
        TriggerHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalStateException("트리거 핸들러 없음: " + type);
        }
        return handler;
    }

    /** 등록된 type (불변 EnumSet 사본) */
    public Set<TriggerType> types() {
        // EnumSet.copyOf(Collection)은 빈 일반 컬렉션에서 IllegalArgumentException이라 noneOf에 담는다
        Set<TriggerType> types = EnumSet.noneOf(TriggerType.class);
        types.addAll(handlers.keySet());
        return Collections.unmodifiableSet(types);
    }
}
