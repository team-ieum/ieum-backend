package com.ieum.auth.repository;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectedAccountRepository extends JpaRepository<ConnectedAccount, UUID> {

    Optional<ConnectedAccount> findByUserIdAndProvider(UUID userId, AuthProvider provider);

    Optional<ConnectedAccount> findByProviderAndProviderAccountId(
        AuthProvider provider, String providerAccountId);

    List<ConnectedAccount> findByUserId(UUID userId);

    boolean existsByUserIdAndProvider(UUID userId, AuthProvider provider);

    /** 회원 탈퇴 시 호출자 트랜잭션 안에서 부른다. */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM ConnectedAccount e WHERE e.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
