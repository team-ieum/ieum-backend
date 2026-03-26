package com.ieum.common.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AesUtil {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final String DELIMITER = ":";

    private final SecretKeySpec secretKey;

    public AesUtil(@Value("${aes.secret-key}") String rawKey) {
        byte[] keyBytes = rawKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalArgumentException("AES 키는 16, 24, 32 바이트여야 합니다.");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    public String decrypt(String encryptedValue) {
        try {
            String[] parts = encryptedValue.split(DELIMITER, 2);
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] cipherText = Base64.getDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("복호화 실패", e);
        }
    }

    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[16];
            new java.security.SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(iv)
                + DELIMITER
                + Base64.getEncoder().encodeToString(cipherText);
        } catch (Exception e) {
            throw new IllegalStateException("암호화 실패", e);
        }
    }
}
