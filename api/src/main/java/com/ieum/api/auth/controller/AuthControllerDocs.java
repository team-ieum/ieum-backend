package com.ieum.api.auth.controller;

import com.ieum.api.auth.dto.LoginRequest;
import com.ieum.api.auth.dto.OAuthTokenExchangeRequest;
import com.ieum.api.auth.dto.RefreshRequest;
import com.ieum.api.auth.dto.RegisterRequest;
import com.ieum.api.auth.dto.RegisterResponse;
import com.ieum.api.auth.dto.ResetPasswordRequest;
import com.ieum.api.auth.dto.SendVerificationCodeRequest;
import com.ieum.api.auth.dto.TokenResponse;
import com.ieum.api.auth.dto.VerifyCodeRequest;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "인증", description = "회원가입, 로그인, 토큰 갱신, 로그아웃, 이메일 인증, 비밀번호 재설정")
public interface AuthControllerDocs {

    @Operation(summary = "회원가입", description = """
        **이메일 인증(purpose=SIGNUP)을 먼저 마쳐야 한다.** 흐름: send-code → verify-code → register.

        인증 완료 표시는 verify-code 성공 후 10분간 유효하고, 가입에 성공하면 소비되어 재사용할 수 없다.

        에러: `EMAIL_ALREADY_EXISTS`(400), `EMAIL_NOT_VERIFIED`(400) — 인증 없음·만료·이미 사용됨
        """)
    ResponseEntity<ApiResponse<RegisterResponse>> register(RegisterRequest request);

    @Operation(summary = "로그인", description = """
        이메일 기준으로 **5회 연속 실패하면 24시간 동안 로그인이 제한**된다. 5번째 실패 응답부터 `LOGIN_LOCKED`(429).
        제한 중에는 올바른 비밀번호여도 거부된다. 가입되지 않은 이메일도 똑같이 횟수를 센다 (가입 여부 노출 방지).
        로그인에 성공하면 실패 횟수가 초기화되고, 비밀번호 재설정(`/password/reset`)에 성공하면 제한이 풀린다.
        마이페이지 비밀번호 변경의 현재 비밀번호 불일치도 같은 횟수에 합산된다.

        에러:
        - `INVALID_CREDENTIALS`(400) — 이메일 또는 비밀번호 불일치
        - `LOGIN_LOCKED`(429) — 실패 횟수 초과, 24시간 제한
        """)
    ResponseEntity<ApiResponse<TokenResponse>> login(LoginRequest request);

    @Operation(summary = "토큰 갱신")
    ResponseEntity<ApiResponse<TokenResponse>> refresh(RefreshRequest request);

    @Operation(summary = "로그아웃")
    ResponseEntity<ApiResponse<Void>> logout(RefreshRequest request);

    @Operation(summary = "OAuth 토큰 교환 (POST /api/v1/auth/oauth/token)", description = "Google 로그인 후 발급된 일회용 코드를 JWT 토큰으로 교환합니다. 코드는 30초 내에 한 번만 사용 가능합니다.")
    ResponseEntity<ApiResponse<TokenResponse>> exchangeOAuthToken(OAuthTokenExchangeRequest request);

    @Operation(summary = "이메일 인증코드 발송", description = """
        6자리 숫자 인증코드를 이메일로 보낸다. 코드는 5분간 유효하다.

        - `SIGNUP`: 이미 가입된 이메일이면 `EMAIL_ALREADY_EXISTS`(400)
        - `PASSWORD_RESET`: 가입되지 않았거나 소셜 로그인 계정이어도 **200을 반환하고 메일만 보내지 않는다** (가입 여부 노출 방지)

        같은 이메일·용도로 30초 안에 다시 요청하면 `VERIFICATION_RESEND_TOO_SOON`(429).
        재발송하면 이전 코드는 무효가 된다.

        에러:
        - `MAIL_SEND_FAILED`(503) — SMTP 장애. 쿨다운 없이 바로 재시도 가능
        - `VERIFICATION_BLOCKED`(429) — 같은 이메일·용도로 인증코드를 5회 틀려 12시간 차단 중
        """)
    ResponseEntity<ApiResponse<Void>> sendVerificationCode(SendVerificationCodeRequest request);

    @Operation(summary = "이메일 인증코드 검증", description = """
        코드가 일치하면 해당 이메일·용도를 10분간 "인증됨"으로 표시한다. 이후 회원가입 또는 비밀번호 재설정을 호출한다.

        에러:
        - `VERIFICATION_CODE_INVALID`(400) — 코드 불일치
        - `VERIFICATION_ATTEMPTS_EXCEEDED`(400) — 5회 틀림. 코드가 폐기되고 **이 이메일·용도는 12시간 동안 발송·검증이 차단**된다
        - `VERIFICATION_BLOCKED`(429) — 12시간 차단 중
        - `VERIFICATION_CODE_EXPIRED`(400) — 만료(5분) 또는 발송 이력 없음
        """)
    ResponseEntity<ApiResponse<Void>> verifyCode(VerifyCodeRequest request);

    @Operation(summary = "비밀번호 재설정", description = """
        **이메일 인증(purpose=PASSWORD_RESET)을 먼저 마쳐야 한다.** 흐름: send-code → verify-code → password/reset.

        성공하면 기존 Refresh Token이 삭제되어 다른 기기의 세션이 끊긴다 (이미 발급된 Access Token은 만료까지 유효).
        로그인 실패로 걸린 24시간 제한(`LOGIN_LOCKED`)도 함께 풀린다.

        에러: `EMAIL_NOT_VERIFIED`(400) — 인증 없음·만료·이미 사용됨
        """)
    ResponseEntity<ApiResponse<Void>> resetPassword(ResetPasswordRequest request);
}
