package com.ieum.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ieum.api.beta.config.BetaPlatformKeyProperties;
import com.ieum.api.beta.service.BetaQuotaService;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.User;
import com.ieum.auth.domain.UserRole;
import com.ieum.auth.repository.UserRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DefaultBetaPlatformProviderTest {

    private UserRepository userRepository;
    private BetaQuotaService betaQuotaService;
    private BetaPlatformKeyProperties properties;
    private DefaultBetaPlatformProvider provider;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        betaQuotaService = Mockito.mock(BetaQuotaService.class);
        properties = new BetaPlatformKeyProperties();
        provider = new DefaultBetaPlatformProvider(userRepository, betaQuotaService, properties);
    }

    private User buildUser(boolean betaAccess) {
        return User.builder()
                .id(userId)
                .email("beta@ieum.com")
                .name("베타유저")
                .provider(AuthProvider.LOCAL)
                .role(UserRole.ROLE_USER)
                .betaAccess(betaAccess)
                .build();
    }

    @Test
    @DisplayName("전역 kill-switch OFF - betaAccess=true여도 비자격")
    void isBetaEligible_globalDisabled_returnsFalse() {
        properties.setEnabled(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(buildUser(true)));

        assertThat(provider.isBetaEligible(userId)).isFalse();
    }

    @Test
    @DisplayName("전역 kill-switch ON + betaAccess=false - 비자격")
    void isBetaEligible_userNotEnrolled_returnsFalse() {
        properties.setEnabled(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(buildUser(false)));

        assertThat(provider.isBetaEligible(userId)).isFalse();
    }

    @Test
    @DisplayName("전역 kill-switch ON + betaAccess=true - 자격 있음")
    void isBetaEligible_enabledAndEnrolled_returnsTrue() {
        properties.setEnabled(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(buildUser(true)));

        assertThat(provider.isBetaEligible(userId)).isTrue();
    }

    @Test
    @DisplayName("사용자를 찾을 수 없으면 비자격")
    void isBetaEligible_userNotFound_returnsFalse() {
        properties.setEnabled(true);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThat(provider.isBetaEligible(userId)).isFalse();
    }

    @Test
    @DisplayName("reserveQuota - BetaQuotaService.checkQuota로 위임")
    void reserveQuota_delegatesToQuotaService() {
        provider.reserveQuota(userId);

        verify(betaQuotaService).checkQuota(userId);
    }

    @Test
    @DisplayName("reserveQuota - 쿼터 초과 시 예외 전파")
    void reserveQuota_exceeded_propagatesException() {
        Mockito.doThrow(new CustomException(ErrorCode.BETA_QUOTA_EXCEEDED))
                .when(betaQuotaService).checkQuota(userId);

        assertThatThrownBy(() -> provider.reserveQuota(userId))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BETA_QUOTA_EXCEEDED);
    }

    @Test
    @DisplayName("recordTokens - BetaQuotaService.addUsedTokens로 위임")
    void recordTokens_delegatesToQuotaService() {
        provider.recordTokens(userId, 1234L);

        verify(betaQuotaService).addUsedTokens(userId, 1234L);
        verify(betaQuotaService, never()).checkQuota(Mockito.any());
    }

    @Test
    @DisplayName("releaseDailyCall - BetaQuotaService.releaseDailyCall로 위임")
    void releaseDailyCall_delegatesToQuotaService() {
        provider.releaseDailyCall(userId);

        verify(betaQuotaService).releaseDailyCall(userId);
    }
}
