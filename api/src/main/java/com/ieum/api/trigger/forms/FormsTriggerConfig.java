package com.ieum.api.trigger.forms;

import com.ieum.api.integration.options.GoogleFormsReader;
import com.ieum.workflowcore.domain.enums.TriggerType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Forms 트리거 핸들러 2개 — 반환 타입이 {@link FormsTriggerHandler}라 레지스트리와 스위퍼가 둘 다 받는다 */
@Configuration
public class FormsTriggerConfig {

    @Bean
    FormsTriggerHandler formsNewResponseTrigger(GoogleFormsReader reader) {
        return new FormsTriggerHandler(TriggerType.FORMS_NEW_RESPONSE, reader);
    }

    @Bean
    FormsTriggerHandler formsAnyResponseTrigger(GoogleFormsReader reader) {
        return new FormsTriggerHandler(TriggerType.FORMS_ANY_RESPONSE, reader);
    }
}
