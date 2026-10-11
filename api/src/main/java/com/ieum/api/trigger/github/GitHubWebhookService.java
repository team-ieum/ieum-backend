package com.ieum.api.trigger.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.executor.IdempotencyStore;
import com.ieum.workflowcore.service.AppTriggerSubscriptionService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

/**
 * GitHub App webhook 수신(spec §4.2 1~4). 서명 → 이벤트 필터 → 이 App이 만든 이슈 제외 → 구독 없는 레포 제외 → delivery 멱등
 * → 비동기 넘김.
 *
 * <p>무인증 엔드포인트라 서명이 유일한 인증이다. 서명 실패·파싱 실패는 본문·헤더 값을 로그에 남기지 않는다.
 * secret이 비면(env 누락 — yml 빈 기본값) 모든 요청 401. Redis 장애 시 {@link IdempotencyStore}는 진행을 허용한다 —
 * 그때의 재전송은 중복 실행될 수 있다.
 *
 * <p>자기 발화 가드: {@code issue.performed_via_github_app.id}(공식 스키마엔 client_id가 없다)를 요청 헤더
 * {@code X-GitHub-Hook-Installation-Target-ID}(GitHub App webhook이면 이 App의 ID)와 비교한다. 헤더는 서명 검증 뒤에만 쓴다 —
 * 서명은 본문만 덮으므로 헤더 자체는 서명되지 않는다. 헤더가 없거나 숫자가 아니면 판정 불가로 보고 통과(디스패치 계속)한다.
 */
@Slf4j
@Service
public class GitHubWebhookService {

    /** GitHub 수동 재전송 창 3일 + 여유 — 재전송도 원본과 같은 delivery id로 온다. */
    static final Duration DELIVERY_TTL = Duration.ofDays(4);
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final Pattern DELIVERY_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final IdempotencyStore idempotencyStore;
    private final GitHubIssueDispatcher dispatcher;
    private final AppTriggerSubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;
    private final byte[] secret;

    public GitHubWebhookService(IdempotencyStore idempotencyStore, GitHubIssueDispatcher dispatcher,
                                AppTriggerSubscriptionService subscriptionService, ObjectMapper objectMapper,
                                @Value("${github.app.webhook-secret}") String secret) {
        this.idempotencyStore = idempotencyStore;
        this.dispatcher = dispatcher;
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    /** @param hookTargetId {@code X-GitHub-Hook-Installation-Target-ID} — 자기 발화 판정에만 쓴다(없으면 null) */
    public void receive(String signature, String event, String deliveryId, String hookTargetId, byte[] body) {
        byte[] raw = body == null ? new byte[0] : body;
        if (!signatureMatches(signature, raw)) {
            log.warn("[GitHubWebhook] 서명 불일치 — 요청 거부");
            throw new CustomException(ErrorCode.UNAUTHORIZED, "웹훅 서명이 올바르지 않습니다.");
        }
        if (!"issues".equals(event)) {
            return;
        }
        JsonNode payload = parse(raw);
        if (!"opened".equals(payload.path("action").asText())) {
            return;
        }
        // IEUM 액션(이 App의 사용자 토큰)이 만든 이슈는 발화하지 않는다 — "새 이슈 → 이슈 생성" 자기 발화 루프(Gmail -in:sent와 같은 결정)
        JsonNode viaApp = payload.path("issue").path("performed_via_github_app").path("id");
        if (viaApp.canConvertToLong() && viaApp.asText().equals(hookTargetId)) {
            log.info("[GitHubWebhook] 이 App이 만든 이슈 — 무시");
            return;
        }
        JsonNode repository = payload.path("repository");
        long repoId = repository.path("id").asLong(0);
        if (repoId <= 0 || !payload.path("issue").isObject()) {
            log.warn("[GitHubWebhook] issues/opened인데 repository.id·issue가 없음 — 무시");
            return;
        }
        // 설치 레포의 이슈는 전부 오므로 구독 없는 레포가 대부분 — 마킹·비동기 큐를 쓰지 않는다. 경합은 디스패처 재조회가 덮는다
        if (subscriptionService.findByResource(TriggerType.GITHUB_NEW_ISSUE, String.valueOf(repoId)).isEmpty()) {
            return;
        }
        if (deliveryId == null || !DELIVERY_ID.matcher(deliveryId).matches()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "X-GitHub-Delivery 헤더가 올바르지 않습니다.");
        }
        String key = "github-delivery:" + deliveryId;
        if (!idempotencyStore.markInFlight(key, DELIVERY_TTL)) {
            log.info("[GitHubWebhook] 이미 받은 delivery — {}", deliveryId);
            return;
        }
        try {
            dispatcher.dispatch(repoId,
                GitHubIssueSource.normalize(payload.path("issue"), repository.path("full_name").asText("")));
        } catch (TaskRejectedException e) {
            // 마커를 남기면 GitHub 화면의 Redeliver까지 중복으로 버려진다
            idempotencyStore.clearInFlight(key);
            log.warn("[GitHubWebhook] 비동기 큐 포화 — delivery: {}", deliveryId);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "웹훅을 지금 처리할 수 없습니다.");
        }
    }

    private boolean signatureMatches(String signature, byte[] raw) {
        if (secret.length == 0 || signature == null || !signature.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String expected = SIGNATURE_PREFIX + HexFormat.of().formatHex(mac.doFinal(raw));
            // 상수 시간 비교 — String.equals는 첫 불일치에서 멈춘다
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256을 쓸 수 없습니다.", e);
        }
    }

    private JsonNode parse(byte[] raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "웹훅 본문이 JSON이 아닙니다.");
        }
    }
}
