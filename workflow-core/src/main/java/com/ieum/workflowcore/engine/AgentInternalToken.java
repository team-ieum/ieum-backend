package com.ieum.workflowcore.engine;

import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;

/**
 * BE→agent 공유 비밀 헤더 {@code X-Internal-Token} (IEUM-BE-106). agent는 값이 맞지 않는 요청을 401로 거절한다.
 *
 * <p>agent를 부르는 WebClient는 전부 빌더에 {@link #headers}를 건다 — api {@code AgentClient},
 * {@code ActionNodeExecutor}·{@code AgentNodeExecutor}·{@code WorkflowValidationClient}. 값({@code ieum.agent.internal-token})이
 * 비면 헤더를 싣지 않는다(agent도 값이 없으면 검사를 끈 채 뜬다 — 배포 순서가 어긋나도 끊기지 않게).
 */
public final class AgentInternalToken {

    public static final String HEADER = "X-Internal-Token";

    private AgentInternalToken() {
    }

    /** {@code WebClient.Builder.defaultHeaders}에 넘길 소비자. 빈 값이면 아무것도 하지 않는다. */
    public static Consumer<HttpHeaders> headers(String token) {
        return headers -> {
            if (token != null && !token.isBlank()) {
                headers.set(HEADER, token);
            }
        };
    }
}
