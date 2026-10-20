package com.ieum.api.user.service;

import com.ieum.api.oauth.service.SlackDisconnectService;
import com.ieum.api.user.dto.UserResponse;
import com.ieum.auth.domain.User;
import com.ieum.auth.repository.UserRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final SlackDisconnectService slackDisconnectService;

    public UserResponse getMyProfile(UUID userId) {
        User user = findUserById(userId);
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse updateMyProfile(UUID userId, String name) {
        User user = findUserById(userId);
        user.updateName(name);
        return UserResponse.from(user);
    }

    @Transactional
    public void deleteMyAccount(UUID userId) {
        User user = findUserById(userId);
        // Slack 링크가 남으면 "남은 링크 0"에 못 닿아 봇 토큰이 영구 잔존한다(spec §3). revoke는 이 트랜잭션 커밋 뒤에 나간다
        slackDisconnectService.disconnect(userId);
        userRepository.delete(user);
    }

    private User findUserById(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
    }
}
