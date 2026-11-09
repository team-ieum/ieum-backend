package com.ieum.workflowcore.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.domain.ApprovalGate;
import com.ieum.workflowcore.engine.executor.ApprovalGateConfig;
import com.ieum.workflowcore.repository.ApprovalGateRepository;
import com.ieum.workflowcore.util.SensitiveDataMasker;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 런타임이 승인 대기로 멈출 때 게이트 행을 남긴다. 화면용 view는 마스킹해 저장한다 —
 * item은 {@code {key: value}} 단위로 {@link SensitiveDataMasker#mask}를 거쳐 키 이름 마스킹({@code apiKey} 등)이 걸리고,
 * message는 웹훅 URL만 가린다.
 */
@Component
@RequiredArgsConstructor
public class ApprovalGateRecorder {

    private final ApprovalGateRepository approvalGateRepository;
    private final ObjectMapper objectMapper;

    /**
     * 그 실행의 기존 게이트 행을 지우고 이번 게이트 집합을 새로 쓴다. 재배달은 RUNNING 실행을 처음부터 다시 돌려
     * 분기·출력이 달라질 수 있어 첫 시도의 행을 남기면 안 된다. 결정은 {@code WAITING_APPROVAL}에서만 생기므로
     * 여기서 지울 결정 행은 없다.
     *
     * @return 가장 이른 게이트 기한
     */
    @Transactional
    public LocalDateTime record(UUID executionId, UUID workflowId, UUID userId,
                                Map<String, ApprovalGateConfig> gates, LocalDateTime now)
            throws JsonProcessingException {
        approvalGateRepository.deleteByExecutionId(executionId);
        LocalDateTime earliest = null;
        for (Map.Entry<String, ApprovalGateConfig> e : gates.entrySet()) {
            ApprovalGateConfig config = e.getValue();
            LocalDateTime deadline = now.plus(config.timeout());
            LocalDateTime remindAt = config.reminder() ? now.plus(config.timeout().dividedBy(2)) : null;
            approvalGateRepository.save(ApprovalGate.open(executionId, workflowId, userId, e.getKey(),
                config.event(), objectMapper.writeValueAsString(maskedView(config)), config.webhookId(),
                deadline, remindAt));
            if (earliest == null || deadline.isBefore(earliest)) earliest = deadline;
        }
        return earliest;
    }

    private static Map<String, Object> maskedView(ApprovalGateConfig config) {
        Map<String, Object> view = config.view();
        view.put("message", SensitiveDataMasker.maskWebhookUrl(config.message()));
        view.put("items", config.items().stream().map(item -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", item.key());
            m.put("value", SensitiveDataMasker.mask(Collections.singletonMap(item.key(), item.value()))
                .get(item.key()));
            return m;
        }).toList());
        return view;
    }
}
