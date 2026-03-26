package com.ieum.api.prompt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ieum.api.prompt.domain.PromptTemplate;
import com.ieum.api.prompt.repository.PromptTemplateRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PromptRendererTest {

    @Mock
    private PromptTemplateRepository promptTemplateRepository;

    @InjectMocks
    private PromptRenderer promptRenderer;

    private UUID templateId;
    private PromptTemplate template;

    @BeforeEach
    void setUp() {
        templateId = UUID.randomUUID();
        template = PromptTemplate.builder()
            .userId(UUID.randomUUID())
            .name("테스트 템플릿")
            .systemMessage("안녕하세요, {{name}}님.")
            .userMessageTemplate("{{task}}를 처리해 주세요.")
            .inputVariables("[]")
            .version(1)
            .isPublic(false)
            .build();
    }

    @Test
    @DisplayName("템플릿만 사용 + 변수 치환 성공")
    void render_withTemplateAndVariables_success() {
        when(promptTemplateRepository.findById(templateId)).thenReturn(Optional.of(template));

        RenderedPrompt result = promptRenderer.render(
            templateId, null, null,
            Map.of("name", "홍길동", "task", "번역"));

        assertThat(result.systemMessage()).isEqualTo("안녕하세요, 홍길동님.");
        assertThat(result.userMessage()).isEqualTo("번역를 처리해 주세요.");
    }

    @Test
    @DisplayName("인라인만 사용")
    void render_withInlineOnly_success() {
        RenderedPrompt result = promptRenderer.render(
            null, "시스템 메시지", "사용자 메시지",
            Collections.emptyMap());

        assertThat(result.systemMessage()).isEqualTo("시스템 메시지");
        assertThat(result.userMessage()).isEqualTo("사용자 메시지");
    }

    @Test
    @DisplayName("템플릿 + 인라인 오버라이드 — 인라인이 모두 있으면 DB 조회 스킵")
    void render_withTemplateAndInlineOverride_success() {
        // 양쪽 인라인이 모두 제공되므로 DB 조회 없이 인라인 값 사용
        RenderedPrompt result = promptRenderer.render(
            templateId, "오버라이드 시스템", "오버라이드 유저",
            Collections.emptyMap());

        assertThat(result.systemMessage()).isEqualTo("오버라이드 시스템");
        assertThat(result.userMessage()).isEqualTo("오버라이드 유저");
    }

    @Test
    @DisplayName("변수 빈 맵으로 치환 시 미치환 변수가 그대로 남음")
    void render_withEmptyVariables_keepUnresolved() {
        when(promptTemplateRepository.findById(templateId)).thenReturn(Optional.of(template));

        RenderedPrompt result = promptRenderer.render(
            templateId, null, null,
            Collections.emptyMap());

        assertThat(result.systemMessage()).isEqualTo("안녕하세요, {{name}}님.");
        assertThat(result.userMessage()).isEqualTo("{{task}}를 처리해 주세요.");
    }

    @Test
    @DisplayName("공백이 있는 변수 패턴 {{ name }}도 치환 성공")
    void render_withSpacedVariablePattern_success() {
        PromptTemplate spacedTemplate = PromptTemplate.builder()
            .userId(UUID.randomUUID())
            .name("공백 테스트")
            .systemMessage("{{ name }}님 환영합니다.")
            .userMessageTemplate("{{ task }} 부탁드립니다.")
            .inputVariables("[]")
            .version(1)
            .isPublic(false)
            .build();
        when(promptTemplateRepository.findById(templateId)).thenReturn(Optional.of(spacedTemplate));

        RenderedPrompt result = promptRenderer.render(
            templateId, null, null,
            Map.of("name", "이순신", "task", "보고서 작성"));

        assertThat(result.systemMessage()).isEqualTo("이순신님 환영합니다.");
        assertThat(result.userMessage()).isEqualTo("보고서 작성 부탁드립니다.");
    }

    @Test
    @DisplayName("templateId가 존재하지 않으면 TEMPLATE_NOT_FOUND")
    void render_withNonExistentTemplateId_throwsTemplateNotFound() {
        when(promptTemplateRepository.findById(templateId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> promptRenderer.render(templateId, null, null, Collections.emptyMap()))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEMPLATE_NOT_FOUND));
    }

    @Test
    @DisplayName("templateId와 inline 둘 다 null이면 INVALID_PROMPT_TEMPLATE")
    void render_withBothNull_throwsInvalidPromptTemplate() {
        assertThatThrownBy(() -> promptRenderer.render(null, null, null, Collections.emptyMap()))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PROMPT_TEMPLATE));
    }

    @Test
    @DisplayName("variables 값이 null이면 빈 문자열로 치환")
    void render_withNullVariableValue_replacedWithEmpty() {
        when(promptTemplateRepository.findById(templateId)).thenReturn(Optional.of(template));

        Map<String, Object> variables = new java.util.HashMap<>();
        variables.put("name", null);
        variables.put("task", "분석");

        RenderedPrompt result = promptRenderer.render(templateId, null, null, variables);

        assertThat(result.systemMessage()).isEqualTo("안녕하세요, 님.");
        assertThat(result.userMessage()).isEqualTo("분석를 처리해 주세요.");
    }
}
