package com.ieum.api.integration.options;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * {@code {app}/{resource}}로 칸 목록 공급원을 골라 동적 칸 목록을 조회한다 (IEUM-BE-105).
 *
 * <p>{@code @Transactional}을 달지 않는다 — {@link IntegrationOptionService}와 같은 이유(공급원의 토큰 갱신 저장이
 * readOnly 트랜잭션에 합류해 유실된다).
 */
@Service
public class IntegrationFieldService {

    private final Map<String, FieldSource> sources;

    public IntegrationFieldService(List<FieldSource> sources) {
        // key 중복이면 기동 실패 — 같은 fieldsSource를 두 빈이 받는 건 설정 오류다.
        this.sources = sources.stream().collect(Collectors.toMap(FieldSource::key, Function.identity()));
    }

    public List<Map<String, Object>> getFields(UUID userId, String app, String resource, Map<String, String> params) {
        FieldSource source = sources.get(app + "." + resource);
        if (source == null) {
            throw new CustomException(ErrorCode.NOT_FOUND, "지원하지 않는 칸 목록입니다.");
        }
        return source.fetch(userId, new HashMap<>(params));
    }
}
