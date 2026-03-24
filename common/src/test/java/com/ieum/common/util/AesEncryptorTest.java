package com.ieum.common.util;

import com.ieum.common.exception.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

class AesEncryptorTest {

    private AesEncryptor aesEncryptor;

    // 정확히 32바이트 키
    private static final String VALID_KEY = "ieum-test-secret-key-32bytes-ok!";

    @BeforeEach
    void setUp() {
        aesEncryptor = new AesEncryptor();
        ReflectionTestUtils.setField(aesEncryptor, "secretKey", VALID_KEY);
        aesEncryptor.validateKey();
    }

    @Test
    void encrypt_decrypt_roundTrip() {
        String plainText = "sk-ant-api03-test-key";
        String encrypted = aesEncryptor.encrypt(plainText);
        String decrypted = aesEncryptor.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(plainText);
    }

    @Test
    void encrypt_samePlainText_producesDifferentCipherText() {
        String plainText = "sk-ant-api03-test-key";
        String encrypted1 = aesEncryptor.encrypt(plainText);
        String encrypted2 = aesEncryptor.encrypt(plainText);
        assertThat(encrypted1).isNotEqualTo(encrypted2);
    }

    @Test
    void decrypt_tamperedCipherText_throwsCustomException() {
        assertThatThrownBy(() -> aesEncryptor.decrypt("invalid-base64!!!"))
                .isInstanceOf(CustomException.class);
    }

    @Test
    void validateKey_invalidLength_throwsIllegalStateException() {
        AesEncryptor invalidEncryptor = new AesEncryptor();
        ReflectionTestUtils.setField(invalidEncryptor, "secretKey", "short-key");

        assertThatThrownBy(invalidEncryptor::validateKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
