package com.ieum.api.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ieum.api.oauth.service.SlackDisconnectService;
import com.ieum.auth.domain.User;
import com.ieum.auth.repository.UserRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private SlackDisconnectService slackDisconnectService;
    @InjectMocks private UserService userService;

    @Test
    @DisplayName("회원 탈퇴 — Slack 해제를 먼저 부르고 사용자를 지운다(링크가 남으면 봇 토큰이 영구 잔존)")
    void deleteMyAccountDisconnectsSlackFirst() {
        UUID userId = UUID.randomUUID();
        User user = mock(User.class);
        given(userRepository.findById(userId)).willReturn(Optional.of(user));

        userService.deleteMyAccount(userId);

        InOrder order = inOrder(slackDisconnectService, userRepository);
        order.verify(slackDisconnectService).disconnect(userId);
        order.verify(userRepository).delete(user);
    }

    @Test
    @DisplayName("없는 사용자면 NOT_FOUND이고 Slack 해제도 부르지 않는다")
    void deleteUnknownUser() {
        UUID userId = UUID.randomUUID();
        given(userRepository.findById(userId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteMyAccount(userId))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);
        verifyNoInteractions(slackDisconnectService);
    }
}
