package com.ieum.workflowcore.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.domain.ApprovalGate;
import com.ieum.workflowcore.engine.executor.ApprovalGateConfig;
import com.ieum.workflowcore.repository.ApprovalGateRepository;
import com.ieum.workflowcore.util.SensitiveDataMasker;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 런타임이 승인 대기로 멈출 때 게이트 행을 남긴다. 화면용 view는 마스킹해 저장한다 —
 * item 값·기본값은 {@code {key: 값}} 단위로 {@link SensitiveDataMasker#mask}를 거쳐 키 이름 마스킹({@code apiKey} 등)이
 * 걸리고, view 전체(message·라벨 등)는 웹훅 URL이 가려진다.
 */
@Component
@RequiredArgsConstructor
public class ApprovalGateRecorder {

    private final ApprovalGateRepository approvalGateRepository;
    private final ObjectMapper objectMapper;

    /**
     * 실행을 승인 대기로 전이하고, 전이했을 때만 그 실행의 기존 게이트 행을 지우고 이번 게이트 집합을 새로 쓴다.
     * 전이({@code pauseAt})와 기록이 한 트랜잭션이다 — 조건부 UPDATE가 행을 잠그고 RUNNING인지 확인하므로,
     * 0행(이미 대기·종료 — 중복 런타임)이면 남의 게이트 결정·알림 시각을 지우지 않는다.
     * 재배달은 RUNNING 실행을 처음부터 다시 돌려 분기·출력이 달라질 수 있어 첫 시도의 행을 남기면 안 된다.
     *
     * @param pauseAt 가장 이른 기한을 받아 대기 전이를 하고 바뀐 행 수를 돌려준다
     * @return 가장 이른 게이트 기한. 전이하지 못했으면 null
     */
    @Transactional(rollbackFor = Exception.class)
    public LocalDateTime record(UUID executionId, UUID workflowId, UUID userId,
                                Map<String, ApprovalGateConfig> gates, LocalDateTime now,
                                ToIntFunction<LocalDateTime> pauseAt) throws JsonProcessingException {
        List<ApprovalGate> rows = new ArrayList<>();
        LocalDateTime earliest = null;
        for (Map.Entry<String, ApprovalGateConfig> e : gates.entrySet()) {
            ApprovalGateConfig config = e.getValue();
            LocalDateTime deadline = now.plus(config.timeout());
            LocalDateTime remindAt = config.reminder() ? now.plus(config.timeout().dividedBy(2)) : null;
            Map<String, Object> view = maskedView(config);
            Object message = view.get("message");
            rows.add(ApprovalGate.open(executionId, workflowId, userId, e.getKey(),
                config.event(), objectMapper.writeValueAsString(view), message == null ? "" : message.toString(),
                config.webhookId(), deadline, remindAt));
            if (earliest == null || deadline.isBefore(earliest)) earliest = deadline;
        }
        if (pauseAt.applyAsInt(earliest) == 0) return null;
        approvalGateRepository.deleteByExecutionId(executionId);
        rows.forEach(approvalGateRepository::save);
        return earliest;
    }

    /**
     * item 값·값 입력 기본값은 {@code {key: 값}} 단위로 가려 키 이름 마스킹({@code token} 등)이 걸리게 하고,
     * 마지막에 view 전체를 한 번 더 가린다(라벨·보기·버튼 문구 속 웹훅 URL).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> maskedView(ApprovalGateConfig config) {
        Map<String, Object> view = config.view();
        view.put("items", config.items().stream().map(item -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", item.key());
            m.put("value", maskByKey(item.key(), item.value()));
            return m;
        }).toList());
        for (Object f : (List<Object>) view.get("fields")) {
            Map<String, Object> field = (Map<String, Object>) f;
            field.put("default", maskByKey((String) field.get("key"), field.get("default")));
        }
        return SensitiveDataMasker.mask(view);
    }

    private static Object maskByKey(String key, Object value) {
        return SensitiveDataMasker.mask(Collections.singletonMap(key, value)).get(key);
    }
}
