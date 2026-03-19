package com.ieum.ai.credential.repository;

import com.ieum.ai.credential.domain.AiProvider;
import com.ieum.ai.credential.domain.Credential;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

import static com.ieum.ai.credential.domain.QCredential.credential;

@Repository
@RequiredArgsConstructor
public class CredentialQueryRepository {

    private final JPAQueryFactory queryFactory;

    public List<Credential> findByUserId(UUID userId) {
        return queryFactory
                .selectFrom(credential)
                .where(credential.userId.eq(userId))
                .orderBy(credential.createdAt.desc())
                .fetch();
    }

    public List<Credential> findByUserIdAndProvider(UUID userId, AiProvider provider) {
        return queryFactory
                .selectFrom(credential)
                .where(
                        credential.userId.eq(userId),
                        credential.provider.eq(provider)
                )
                .orderBy(credential.createdAt.desc())
                .fetch();
    }

    public boolean existsByUserIdAndProviderAndDisplayName(UUID userId, AiProvider provider, String displayName) {
        return queryFactory
                .selectOne()
                .from(credential)
                .where(
                        credential.userId.eq(userId),
                        credential.provider.eq(provider),
                        credential.displayName.eq(displayName)
                )
                .fetchFirst() != null;
    }
}
