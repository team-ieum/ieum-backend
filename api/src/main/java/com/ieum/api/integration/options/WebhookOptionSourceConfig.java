package com.ieum.api.integration.options;

import com.ieum.api.webhookcredential.domain.WebhookProvider;
import com.ieum.api.webhookcredential.repository.WebhookCredentialRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 웹훅 공급원 3키 — 구현은 {@link WebhookOptionSource} 하나, provider 필터만 다르다 (IEUM-BE-76). */
@Configuration
public class WebhookOptionSourceConfig {

    @Bean
    public OptionSource ieumWebhooksOptionSource(WebhookCredentialRepository repository) {
        return new WebhookOptionSource("ieum.webhooks", null, repository);
    }

    @Bean
    public OptionSource slackWebhooksOptionSource(WebhookCredentialRepository repository) {
        return new WebhookOptionSource("slack.webhooks", WebhookProvider.SLACK, repository);
    }

    @Bean
    public OptionSource discordWebhooksOptionSource(WebhookCredentialRepository repository) {
        return new WebhookOptionSource("discord.webhooks", WebhookProvider.DISCORD, repository);
    }
}
