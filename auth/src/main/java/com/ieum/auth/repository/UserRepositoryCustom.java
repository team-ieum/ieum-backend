package com.ieum.auth.repository;

import com.ieum.auth.domain.User;
import java.util.Optional;

public interface UserRepositoryCustom {

    Optional<User> findByEmailWithQueryDsl(String email);
}
