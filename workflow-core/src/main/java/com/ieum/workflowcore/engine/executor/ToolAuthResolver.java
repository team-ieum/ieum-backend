package com.ieum.workflowcore.engine.executor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolAuthResolver {

    private static final String NOTION_BUILTIN_PREFIX = "builtin:notion_";
    private static final Map<String, String> HEADER_BY_TOOL_PREFIX = Map.of(
        NOTION_BUILTIN_PREFIX, "X-Notion-Token"
    );

    private final CredentialProvider credentialProvider;

    public Map<String, String> resolveHeaders(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (Map<String, Object> tool : tools) {
            String toolName = getString(tool, "name").orElse(null);
            if (toolName == null) {
                continue;
            }

            String headerName = resolveHeaderName(toolName).orElse(null);
            if (headerName == null) {
                continue;
            }

            resolveAuthValue(tool, toolName)
                .ifPresentOrElse(
                    value -> headers.put(headerName, value),
                    () -> log.warn("[ToolAuthResolver] 빌트인 도구 사용이지만 auth 설정이 없음 — toolName: {}", toolName)
                );
        }

        return headers;
    }

    private Optional<String> resolveHeaderName(String toolName) {
        return HEADER_BY_TOOL_PREFIX.entrySet().stream()
            .filter(entry -> toolName.startsWith(entry.getKey()))
            .map(Map.Entry::getValue)
            .findFirst();
    }

    private Optional<String> resolveAuthValue(Map<String, Object> tool, String toolName) {
        Object auth = tool.get("auth");
        if (auth instanceof Map<?, ?> authMap) {
            return resolveStructuredAuth(authMap, toolName);
        }

        return resolveCredential(getString(tool, "credentialId"), toolName);
    }

    private Optional<String> resolveStructuredAuth(Map<?, ?> auth, String toolName) {
        Optional<String> credentialId = getString(auth, "credentialId");
        String type = getString(auth, "type")
            .orElseGet(() -> credentialId.isPresent() ? "credential" : null);

        if (type == null) {
            return Optional.empty();
        }

        return switch (type.toLowerCase(Locale.ROOT)) {
            case "credential" -> resolveCredential(credentialId, toolName);
            case "secret", "plain" -> getString(auth, "value");
            default -> {
                log.warn("[ToolAuthResolver] 지원하지 않는 도구 auth type — toolName: {}, type: {}", toolName, type);
                yield Optional.empty();
            }
        };
    }

    private Optional<String> resolveCredential(Optional<String> credentialId, String toolName) {
        return credentialId
            .filter(id -> !id.isBlank())
            .map(id -> {
                log.debug("[ToolAuthResolver] 도구 인증 credential 조회 — toolName: {}", toolName);
                return credentialProvider.getDecryptedApiKey(id);
            });
    }

    private Optional<String> getString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return Optional.of(stringValue);
        }
        return Optional.empty();
    }
}
