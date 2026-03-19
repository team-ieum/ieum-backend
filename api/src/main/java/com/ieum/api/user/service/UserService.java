package com.ieum.api.user.service;

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
        userRepository.delete(user);
    }

    private User findUserById(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
    }
}
