package com.ieum.api.beta.config;

import java.util.List;
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

    /**
     * 재시도 모델 fallback이 platform 모드에서 시도할 수 있는 모델 화이트리스트.
     * platform 모드는 Gemini 키 한 장이라 여기 없는(비-Gemini) 모델로 fallback하면
     * agent resolve가 실패한다 — 기본값은 베타가 실제 쓰는 단일 모델로 좁게 잡는다.
     * 넓히려면 yml만 바꾸면 된다(코드 변경 불필요).
     */
    private List<String> allowedModels = List.of("gemini-3.5-flash");
}
