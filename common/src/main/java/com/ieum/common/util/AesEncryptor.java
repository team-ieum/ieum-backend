package com.ieum.common.util;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
@Component
public class AesEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    @Value("${aes.secret-key}")
    private String secretKey;

    @PostConstruct
    public void validateKey() {
        byte[] keyBytes = secretKey.getBytes(UTF_8);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                "AES secret key must be exactly 32 bytes (256-bit), but was " + keyBytes.length + " bytes."
            );
        }
    }

    public String encrypt(String plainText) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(UTF_8), "AES");

            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] encrypted = cipher.doFinal(plainText.getBytes(UTF_8));

            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "Encryption failed");
        }
    }

    public String decrypt(String encryptedText) {
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedText);

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            byte[] cipherText = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, cipherText, 0, cipherText.length);

            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(UTF_8), "AES");
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] decrypted = cipher.doFinal(cipherText);
            return new String(decrypted, UTF_8);
        } catch (IllegalArgumentException e) {
            log.error("[AesEncryptor] Base64 디코딩 실패 - 저장된 데이터가 손상되었을 수 있습니다", e);
            throw new CustomException(ErrorCode.CREDENTIAL_DECRYPT_FAILED);
        } catch (AEADBadTagException e) {
            log.error("[AesEncryptor] GCM 태그 검증 실패 - 키 불일치 또는 데이터 변조 의심", e);
            throw new CustomException(ErrorCode.CREDENTIAL_DECRYPT_FAILED);
        } catch (Exception e) {
            log.error("[AesEncryptor] 복호화 중 예상치 못한 예외 발생", e);
            throw new CustomException(ErrorCode.CREDENTIAL_DECRYPT_FAILED);
        }
    }
}
