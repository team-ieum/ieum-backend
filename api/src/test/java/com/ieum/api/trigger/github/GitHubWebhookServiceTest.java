package com.ieum.api.trigger.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.AppTriggerSubscription;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.executor.IdempotencyStore;
import com.ieum.workflowcore.service.AppTriggerSubscriptionService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskRejectedException;

class GitHubWebhookServiceTest {

    private static final String SECRET = "test-github-webhook-secret";
    /** X-GitHub-Hook-Installation-Target-ID — GitHub App webhook이면 이 App의 ID. */
    private static final String HOOK_TARGET = "1001";
    private static final String DELIVERY = "72d3162e-cc78-11e3-81ab-4c9367dc0958";
    private static final String OPENED = """
        {"action": "opened",
         "issue": {"number": 7, "title": "버그", "body": "본문", "html_url": "https://github.com/dobee/ieum/issues/7",
                   "user": {"login": "octo"}, "labels": [], "created_at": "2026-10-11T01:02:03Z"},
         "repository": {"id": 123, "full_name": "dobee/ieum"}}""";

    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final GitHubIssueDispatcher dispatcher = mock(GitHubIssueDispatcher.class);
    private final AppTriggerSubscriptionService subscriptions = mock(AppTriggerSubscriptionService.class);
    private final GitHubWebhookService service =
        new GitHubWebhookService(idempotency, dispatcher, subscriptions, new ObjectMapper(), SECRET);

    /** {@code appId} App이 사용자 토큰으로 만든 이슈의 opened payload — 공식 스키마 모양(client_id 없음). */
    private static String openedBy(long appId) {
        return OPENED.replace("\"labels\": []", "\"labels\": [], \"performed_via_github_app\": {\"id\": " + appId
            + ", \"slug\": \"some-app\", \"node_id\": \"MDM6QXBwMQ==\", \"name\": \"Some App\"}");
    }

    /** 기본은 repo 123에 구독 1건 — 구독 없음 케이스만 덮어쓴다. */
    @BeforeEach
    void subscribed() {
        given(subscriptions.findByResource(TriggerType.GITHUB_NEW_ISSUE, "123"))
            .willReturn(List.of(mock(AppTriggerSubscription.class)));
    }

    static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CustomException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    @Test
    @DisplayName("[Review Focus 1] issues/opened — delivery 마킹(4일 — 재전송 창 3일) 후 정규화 출력으로 디스패치")
    @SuppressWarnings("unchecked")
    void openedIssueIsDispatched() throws Exception {
        given(idempotency.markInFlight("github-delivery:" + DELIVERY, Duration.ofDays(4))).willReturn(true);
        byte[] body = bytes(OPENED);

        service.receive(sign(SECRET, body), "issues", DELIVERY, HOOK_TARGET, body);

        ArgumentCaptor<Map<String, Object>> output = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).dispatch(eq(123L), output.capture());
        assertThat(output.getValue()).containsEntry("issueNumber", 7).containsEntry("repo", "dobee/ieum")
            .doesNotContainKey("action");
    }

    @Test
    @DisplayName("[Review Focus 3] 위조·누락·접두 없는 서명은 401 — 파싱·마킹·디스패치 없음")
    void forgedSignatureIs401WithoutSideEffects() throws Exception {
        byte[] body = bytes(OPENED);
        assertErrorCode(() -> service.receive(sign("wrong-secret", body), "issues", DELIVERY, HOOK_TARGET, body), ErrorCode.UNAUTHORIZED);
        assertErrorCode(() -> service.receive(null, "issues", DELIVERY, HOOK_TARGET, body), ErrorCode.UNAUTHORIZED);
        assertErrorCode(() -> service.receive(sign(SECRET, body).substring(7), "issues", DELIVERY, HOOK_TARGET, body), ErrorCode.UNAUTHORIZED);
        // 본문이 한 바이트라도 바뀌면 불일치
        assertErrorCode(() -> service.receive(sign(SECRET, body), "issues", DELIVERY, HOOK_TARGET, bytes(OPENED + " ")), ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(idempotency, dispatcher);
    }

    @Test
    @DisplayName("[Review Focus 3] secret 미설정(env 누락 = 빈 기본값)이면 어떤 서명이든 401(fail closed)")
    void blankSecretRejectsEverything() throws Exception {
        GitHubWebhookService unset = new GitHubWebhookService(idempotency, dispatcher, subscriptions, new ObjectMapper(), "");
        byte[] body = bytes(OPENED);
        // 빈 키 HMAC는 JDK가 만들지 못한다(SecretKeySpec: Empty key) — 서비스는 서명 계산 전에 blank secret을 거부하므로 아무 서명이나 넣는다
        assertErrorCode(() -> unset.receive(sign("any-secret", body), "issues", DELIVERY, HOOK_TARGET, body), ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(idempotency, dispatcher);
    }

    @Test
    @DisplayName("[Review Focus 3] GitHub 문서 테스트 벡터 서명을 받는다 — 접두·소문자 hex·UTF-8 형식이 GitHub과 같다")
    void githubDocVector() {
        GitHubWebhookService docs =
            new GitHubWebhookService(idempotency, dispatcher, subscriptions, new ObjectMapper(), "It's a Secret to Everybody");
        // docs.github.com validating-webhook-deliveries — ping은 서명 통과 후 202로 끝나므로 예외가 없으면 형식 일치
        docs.receive("sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17",
            "ping", DELIVERY, HOOK_TARGET, bytes("Hello, World!"));
        verifyNoInteractions(idempotency, dispatcher);
    }

    @Test
    @DisplayName("[Review Focus 9] performed_via_github_app.id == Hook-Installation-Target-ID(이 App)면 마킹·디스패치 없이 끝(202)")
    void issueCreatedByThisAppIsIgnored() throws Exception {
        byte[] ours = bytes(openedBy(1001));
        service.receive(sign(SECRET, ours), "issues", DELIVERY, HOOK_TARGET, ours);
        verifyNoInteractions(idempotency, dispatcher);
    }

    @Test
    @DisplayName("[Review Focus 9] 다른 App이 만든 이슈는 디스패치")
    void issueCreatedByOtherAppIsDispatched() throws Exception {
        given(idempotency.markInFlight(any(), any())).willReturn(true);
        byte[] other = bytes(openedBy(2002));
        service.receive(sign(SECRET, other), "issues", DELIVERY, HOOK_TARGET, other);
        verify(dispatcher).dispatch(eq(123L), anyMap());
    }

    @Test
    @DisplayName("[Review Focus 9] Hook-Installation-Target-ID 헤더가 없거나 숫자가 아니면 판정 불가 — 통과(디스패치)")
    void missingHookTargetCannotJudgeSoDispatches() throws Exception {
        given(idempotency.markInFlight(any(), any())).willReturn(true);
        byte[] ours = bytes(openedBy(1001));
        service.receive(sign(SECRET, ours), "issues", DELIVERY, null, ours);
        service.receive(sign(SECRET, ours), "issues", DELIVERY, "not-a-number", ours);
        verify(dispatcher, org.mockito.Mockito.times(2)).dispatch(eq(123L), anyMap());
    }

    @Test
    @DisplayName("구독 0건 레포의 issues/opened는 delivery 마킹·디스패치 없이 끝(202)")
    void repoWithoutSubscriptionIsIgnored() throws Exception {
        given(subscriptions.findByResource(TriggerType.GITHUB_NEW_ISSUE, "123")).willReturn(List.of());
        byte[] body = bytes(OPENED);

        service.receive(sign(SECRET, body), "issues", DELIVERY, HOOK_TARGET, body);

        verifyNoInteractions(idempotency, dispatcher);
    }

    @Test
    @DisplayName("issues가 아닌 이벤트(ping 등)·opened가 아닌 action은 마킹 없이 끝(202)")
    void otherEventsAreIgnored() throws Exception {
        byte[] ping = bytes("{\"zen\": \"hi\"}");
        service.receive(sign(SECRET, ping), "ping", DELIVERY, HOOK_TARGET, ping);
        byte[] closed = bytes(OPENED.replace("\"opened\"", "\"closed\""));
        service.receive(sign(SECRET, closed), "issues", DELIVERY, HOOK_TARGET, closed);
        verifyNoInteractions(idempotency, dispatcher);
    }

    @Test
    @DisplayName("[Review Focus 1] 이미 받은 delivery면 디스패치하지 않는다")
    void duplicateDeliveryIsAcceptedWithoutDispatch() throws Exception {
        given(idempotency.markInFlight(any(), any())).willReturn(false);
        byte[] body = bytes(OPENED);

        service.receive(sign(SECRET, body), "issues", DELIVERY, HOOK_TARGET, body);

        verify(dispatcher, org.mockito.Mockito.never()).dispatch(anyLong(), anyMap());
    }

    @Test
    @DisplayName("delivery 헤더가 없거나 형식이 이상하면 400, 서명 맞는 깨진 JSON도 400")
    void badDeliveryOrJson() throws Exception {
        byte[] body = bytes(OPENED);
        assertErrorCode(() -> service.receive(sign(SECRET, body), "issues", null, HOOK_TARGET, body), ErrorCode.INVALID_INPUT);
        assertErrorCode(() -> service.receive(sign(SECRET, body), "issues", "x".repeat(65), HOOK_TARGET, body), ErrorCode.INVALID_INPUT);
        byte[] broken = bytes("{not json");
        assertErrorCode(() -> service.receive(sign(SECRET, broken), "issues", DELIVERY, HOOK_TARGET, broken), ErrorCode.INVALID_INPUT);
        verifyNoInteractions(dispatcher);
    }

    @Test
    @DisplayName("비동기 큐가 거절하면 마커를 지우고 500 — GitHub에서 재전송할 수 있게")
    void rejectedDispatchClearsMarker() throws Exception {
        given(idempotency.markInFlight(any(), any())).willReturn(true);
        willThrow(new TaskRejectedException("full")).given(dispatcher).dispatch(anyLong(), anyMap());
        byte[] body = bytes(OPENED);

        assertErrorCode(() -> service.receive(sign(SECRET, body), "issues", DELIVERY, HOOK_TARGET, body), ErrorCode.INTERNAL_SERVER_ERROR);
        verify(idempotency).clearInFlight("github-delivery:" + DELIVERY);
    }
}
