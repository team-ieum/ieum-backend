package com.ieum.workflowcore.catalog;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CatalogConfig {

    /** 기동 때 한 번 읽는다. 서브모듈이 비었거나 불변식을 어기면 IllegalStateException으로 기동이 실패한다. */
    @Bean
    public EventCatalog eventCatalog() {
        return EventCatalog.loadFromClasspath();
    }
}
