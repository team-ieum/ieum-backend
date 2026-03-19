package com.ieum.auth.repository;

import static com.ieum.auth.domain.QUser.user;

import com.ieum.auth.domain.User;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<User> findByEmailWithQueryDsl(String email) {
        User result = queryFactory
            .selectFrom(user)
            .where(user.email.eq(email))
            .fetchOne();
        return Optional.ofNullable(result);
    }
}
