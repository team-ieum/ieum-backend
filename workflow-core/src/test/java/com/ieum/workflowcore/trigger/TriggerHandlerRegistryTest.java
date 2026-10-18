package com.ieum.workflowcore.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.workflowcore.domain.enums.TriggerType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TriggerHandlerRegistryTest {

    @Test
    @DisplayName("type으로 핸들러를 찾고, types()는 등록된 type의 불변 사본이다")
    void getAndTypes() {
        ManualTriggerHandler manual = new ManualTriggerHandler();
        TriggerHandler gmail = () -> TriggerType.GMAIL_NEW_EMAIL;

        TriggerHandlerRegistry registry = new TriggerHandlerRegistry(List.<TriggerHandler>of(manual, gmail));

        assertThat(registry.get(TriggerType.MANUAL)).isSameAs(manual);
        assertThat(registry.get(TriggerType.GMAIL_NEW_EMAIL)).isSameAs(gmail);
        assertThat(registry.types()).containsExactlyInAnyOrder(TriggerType.MANUAL, TriggerType.GMAIL_NEW_EMAIL);
        assertThatThrownBy(() -> registry.types().add(TriggerType.WEBHOOK))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("같은 type 핸들러가 둘이면 기동 실패 — 어느 쪽이 이길지 스캔 순서에 맡기지 않는다")
    void duplicateTypeFailsFast() {
        List<TriggerHandler> handlers = List.of(new WebhookTriggerHandler(), () -> TriggerType.WEBHOOK);

        assertThatThrownBy(() -> new TriggerHandlerRegistry(handlers))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("트리거 핸들러 중복: WEBHOOK");
    }

    @Test
    @DisplayName("없는 type 조회는 IllegalStateException(fail-closed), 빈 레지스트리의 types()는 빈 집합")
    void missingTypeFailsClosed() {
        TriggerHandlerRegistry registry = new TriggerHandlerRegistry(List.of());

        assertThatThrownBy(() -> registry.get(TriggerType.SCHEDULE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("트리거 핸들러 없음: SCHEDULE");
        assertThat(registry.types()).isEmpty();
    }
}
