package com.ieum.auth.domain;

/** 이메일 인증코드의 용도. Redis 키에 포함되어 한 용도의 인증을 다른 용도에 쓸 수 없다. */
public enum VerificationPurpose {
    SIGNUP,
    PASSWORD_RESET
}
