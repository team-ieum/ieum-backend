package com.ieum.api.credential.domain;

import com.ieum.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_ai_credentials")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserAiCredential extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "credential_type", nullable = false, length = 10)
    private String credentialType;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "encrypted_api_key", columnDefinition = "TEXT")
    private String encryptedApiKey;

    @Column(name = "key_hint", length = 20)
    private String keyHint;

    @Column(name = "is_valid", nullable = false)
    private boolean isValid;

    @Column(name = "last_validated_at")
    private LocalDateTime lastValidatedAt;
}
