package com.ieum.ai.credential.domain;

import com.ieum.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_ai_credentials")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Credential extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_type", nullable = false, length = 10)
    private CredentialType credentialType;

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

    public void updateValidation(boolean isValid) {
        this.isValid = isValid;
        this.lastValidatedAt = LocalDateTime.now();
    }

    public void updateDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void updateApiKey(String encryptedApiKey, String keyHint) {
        this.encryptedApiKey = encryptedApiKey;
        this.keyHint = keyHint;
    }
}
