package com.ieum.api.beta.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 베타 플랫폼 키(IEUM 소유 Gemini 키) 폴백 설정.
 *
 * <p>{@code application.yml}의 {@code ieum.beta.platform-key} 설정을 읽는다.
 * BYOK 없이 베타 초대 사용자가 플랫폼 키로 체험할 수 있게 하는 전역 kill-switch와 쿼터 값을 담는다.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "ieum.beta.platform-key")
public class BetaPlatformKeyProperties {

    /** 전역 kill-switch. false면 베타 플랫폼 키 폴백을 완전히 비활성화. 기본값 false. */
    private boolean enabled = false;

    /** 사용자당 일일 호출 허용 횟수. 기본값 30. */
    private int dailyCallQuota = 30;

    /** 사용자당 누적 토큰 예산(총량, 리셋 없음). 기본값 5,000,000. */
    private long tokenBudget = 5_000_000L;
}
