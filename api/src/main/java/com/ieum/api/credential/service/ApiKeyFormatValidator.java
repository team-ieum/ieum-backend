package com.ieum.api.credential.service;

import com.ieum.api.credential.domain.AiProvider;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;

public class ApiKeyFormatValidator {

    private static final int MIN_KEY_LENGTH = 20;

    private ApiKeyFormatValidator() {}

    public static void validate(AiProvider provider, String rawApiKey) {
        if (rawApiKey.length() < MIN_KEY_LENGTH) {
            throw new CustomException(ErrorCode.INVALID_API_KEY_FORMAT, "API 키가 너무 짧습니다.");
        }

        switch (provider) {
            case CLAUDE -> {
                if (!rawApiKey.startsWith("sk-ant-")) {
                    throw new CustomException(ErrorCode.INVALID_API_KEY_FORMAT,
                            "CLAUDE API 키는 'sk-ant-'로 시작해야 합니다.");
                }
            }
            case OPENAI -> {
                if (!rawApiKey.startsWith("sk-")) {
                    throw new CustomException(ErrorCode.INVALID_API_KEY_FORMAT,
                            "OPENAI API 키는 'sk-'로 시작해야 합니다.");
                }
            }
            case GEMINI -> {
                if (!rawApiKey.startsWith("AIzaSy")) {
                    throw new CustomException(ErrorCode.INVALID_API_KEY_FORMAT,
                            "GEMINI API 키는 'AIzaSy'로 시작해야 합니다.");
                }
            }
        }
    }
}
