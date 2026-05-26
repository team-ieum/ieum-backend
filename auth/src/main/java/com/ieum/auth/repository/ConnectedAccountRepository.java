package com.ieum.auth.repository;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectedAccountRepository extends JpaRepository<ConnectedAccount, UUID> {

    Optional<ConnectedAccount> findByUserIdAndProvider(UUID userId, AuthProvider provider);

    List<ConnectedAccount> findByUserId(UUID userId);

    boolean existsByUserIdAndProvider(UUID userId, AuthProvider provider);
}