package com.ieum.auth.delegation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.auth.delegation.DelegationTokenService.Delegation;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import com.ieum.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 위임 토큰 필터 (IEUM-BE-106) — {@code X-Delegation-Token} 헤더가 있을 때만 동작한다. 일반 JWT와 키·필터를 공유하지 않는다.
 *
 * <p>허용 경로(메서드 포함) 5개 밖이면 401, 워크플로우 범위 경로의 {@code workflowId}가 토큰 것과 다르면 403.
 * 통과하면 토큰 사용자로 {@link CustomUserDetails#of}(이메일 없음) + role authority를 세운다 — 대상 컨트롤러는
 * {@code getId()}만 쓴다. {@code SecurityConfig}가 {@code JwtAuthenticationFilter} 앞에 건다.
 *
 * <p>{@code ieum.agent.internal-token}이 비어 있지 않으면 {@code X-Internal-Token}도 일치해야 한다(누락·불일치 401,
 * 토큰 조회 전) — 위임 토큰만 새 나가도 agent 밖에서는 쓰지 못하게. 비면 검사하지 않는다(agent와 같은 배포 순서 규칙).
 */
@Slf4j
@Component
public class DelegationTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Delegation-Token";
    /** workflow-core {@code AgentInternalToken.HEADER}와 같은 이름 — auth는 common에만 기대므로 문자열로 둔다. */
    static final String INTERNAL_HEADER = "X-Internal-Token";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    /** 토큰 userId로만 동작하는 조회 — 경로에 워크플로우가 없다. */
    private static final List<RequestMatcher> USER_SCOPED = List.of(
        PATHS.matcher(HttpMethod.GET, "/api/v1/integrations/{app}/options/{resource}"),
        PATHS.matcher(HttpMethod.GET, "/api/v1/integrations/{app}/fields/{resource}"),
        PATHS.matcher(HttpMethod.GET, "/api/v1/oauth/connections"));

    /** 경로 {@code workflowId}가 토큰 workflowId와 같아야 하는 경로. */
    private static final List<PathPatternRequestMatcher> WORKFLOW_SCOPED = List.of(
        PATHS.matcher(HttpMethod.POST, "/api/v1/workflows/{workflowId}/nodes/{nodeId}/test"),
        PATHS.matcher(HttpMethod.GET, "/api/v1/workflows/{workflowId}/nodes/{nodeId}/sample"));

    private final DelegationTokenService delegationTokenService;
    private final byte[] internalToken;

    public DelegationTokenFilter(DelegationTokenService delegationTokenService,
                                 @Value("${ieum.agent.internal-token:}") String internalToken) {
        this.delegationTokenService = delegationTokenService;
        this.internalToken = internalToken == null || internalToken.isBlank()
            ? null : internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {

        String token = request.getHeader(HEADER);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (internalToken != null && !internalTokenMatches(request.getHeader(INTERNAL_HEADER))) {
            log.warn("[Delegation] X-Internal-Token 누락·불일치 — {} {}", request.getMethod(), request.getRequestURI());
            reject(response, ErrorCode.UNAUTHORIZED);
            return;
        }

        Optional<Delegation> found = StringUtils.hasText(token) ? delegationTokenService.resolve(token) : Optional.empty();
        if (found.isEmpty() || !StringUtils.hasText(found.get().role())) {
            reject(response, ErrorCode.UNAUTHORIZED);
            return;
        }
        Delegation delegation = found.get();

        if (USER_SCOPED.stream().noneMatch(matcher -> matcher.matches(request))) {
            Optional<String> pathWorkflowId = WORKFLOW_SCOPED.stream()
                .map(matcher -> matcher.matcher(request))
                .filter(RequestMatcher.MatchResult::isMatch)
                .map(result -> result.getVariables().get("workflowId"))
                .findFirst();
            if (pathWorkflowId.isEmpty()) {
                log.warn("[Delegation] 허용 경로 밖 — {} {}", request.getMethod(), request.getRequestURI());
                reject(response, ErrorCode.UNAUTHORIZED);
                return;
            }
            if (!sameWorkflow(pathWorkflowId.get(), delegation.workflowId())) {
                log.warn("[Delegation] 다른 워크플로우 접근 — userId: {}", delegation.userId());
                reject(response, ErrorCode.FORBIDDEN);
                return;
            }
        }

        CustomUserDetails user = CustomUserDetails.of(delegation.userId(), "", delegation.role());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        filterChain.doFilter(request, response);
    }

    private boolean internalTokenMatches(String header) {
        return header != null && MessageDigest.isEqual(internalToken, header.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean sameWorkflow(String pathWorkflowId, UUID tokenWorkflowId) {
        try {
            return UUID.fromString(pathWorkflowId).equals(tokenWorkflowId);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void reject(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSON.writeValueAsString(ApiResponse.error(code)));
    }
}
