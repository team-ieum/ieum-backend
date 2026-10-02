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
 * {@code {app}/{resource}}로 공급원을 골라 선택지를 조회한다 (IEUM-BE-71).
 *
 * <p>{@code @Transactional}을 달지 않는다 — 공급원이 부르는 {@code GoogleTokenService}가 갱신 토큰을
 * 자체 트랜잭션으로 저장하는데, 여기 readOnly 트랜잭션이 있으면 합류해 저장이 유실된다.
 */
@Service
public class IntegrationOptionService {

    private final Map<String, OptionSource> sources;

    public IntegrationOptionService(List<OptionSource> sources) {
        // key 중복이면 기동 실패 — 같은 optionsSource를 두 빈이 받는 건 설정 오류다.
        this.sources = sources.stream().collect(Collectors.toMap(OptionSource::key, Function.identity()));
    }

    public OptionPage fetch(UUID userId, String app, String resource, Map<String, String> params) {
        OptionSource source = sources.get(app + "." + resource);
        if (source == null) {
            throw new CustomException(ErrorCode.NOT_FOUND, "지원하지 않는 옵션 목록입니다.");
        }
        Map<String, String> inputs = new HashMap<>(params);
        String cursor = inputs.remove("cursor");
        return source.fetch(userId, inputs, cursor);
    }
}
