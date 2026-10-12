package com.ieum.auth.service;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.EmailVerificationCode;
import com.ieum.auth.domain.EmailVerified;
import com.ieum.auth.domain.VerificationPurpose;
import com.ieum.auth.repository.EmailVerificationCodeRepository;
import com.ieum.auth.repository.EmailVerifiedRepository;
import com.ieum.auth.repository.UserRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 이메일 인증코드 발송·검증. 상태는 전부 Redis TTL로 관리한다.
 *
 * <p>흐름: {@link #sendCode} → {@link #verifyCode} (성공 시 "인증됨" 표시) →
 * 회원가입/비밀번호 재설정에서 {@link #consumeVerification}으로 표시를 1회 소비.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    static final long CODE_TTL_SECONDS = 300;
    static final long VERIFIED_TTL_SECONDS = 600;
    static final long RESEND_COOLDOWN_MILLIS = 30_000;
    static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailVerificationCodeRepository codeRepository;
    private final EmailVerifiedRepository verifiedRepository;
    private final UserRepository userRepository;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String from;

    public void sendCode(String email, VerificationPurpose purpose) {
        if (!isSendTarget(email, purpose)) {
            return;
        }

        String key = key(purpose, email);
        long now = System.currentTimeMillis();
        codeRepository.findById(key)
            .filter(saved -> now - saved.getSentAt() < RESEND_COOLDOWN_MILLIS)
            .ifPresent(saved -> {
                throw new CustomException(ErrorCode.VERIFICATION_RESEND_TOO_SOON);
            });

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        // 발송 성공 후에 저장한다 — 발송이 실패하면 코드가 남지 않아 쿨다운 없이 바로 재시도할 수 있다.
        send(email, purpose, code);
        codeRepository.save(EmailVerificationCode.builder()
            .id(key)
            .code(code)
            .attempts(0)
            .sentAt(now)
            .expiresAt(now + CODE_TTL_SECONDS * 1000)
            .ttl(CODE_TTL_SECONDS)
            .build());
    }

    // ponytail: 시도 횟수는 read-modify-write라 동시 요청이 몰리면 5회를 약간 넘길 수 있다. 6자리 코드에서 의미 있는 차이가 아니라 원자 연산(INCR)은 생략.
    public void verifyCode(String email, String code, VerificationPurpose purpose) {
        String key = key(purpose, email);
        EmailVerificationCode saved = codeRepository.findById(key)
            .orElseThrow(() -> new CustomException(ErrorCode.VERIFICATION_CODE_EXPIRED));

        if (!MessageDigest.isEqual(
                saved.getCode().getBytes(StandardCharsets.UTF_8),
                code.getBytes(StandardCharsets.UTF_8))) {
            saved.increaseAttempts(System.currentTimeMillis());
            if (saved.getAttempts() >= MAX_ATTEMPTS) {
                codeRepository.deleteById(key);
                throw new CustomException(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED);
            }
            codeRepository.save(saved);
            throw new CustomException(ErrorCode.VERIFICATION_CODE_INVALID);
        }

        codeRepository.deleteById(key);
        verifiedRepository.save(EmailVerified.builder()
            .id(key)
            .ttl(VERIFIED_TTL_SECONDS)
            .build());
    }

    /** 인증 완료 표시를 확인하고 즉시 삭제한다. 표시가 없으면 {@code EMAIL_NOT_VERIFIED}. */
    public void consumeVerification(String email, VerificationPurpose purpose) {
        String key = key(purpose, email);
        if (!verifiedRepository.existsById(key)) {
            throw new CustomException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        verifiedRepository.deleteById(key);
    }

    /**
     * 발송 대상인지 판단한다.
     *
     * <p>SIGNUP: 이미 가입된 이메일이면 거부한다. register가 어차피 같은 에러로 가입 여부를 알려주므로
     * 숨길 정보가 없다.
     *
     * <p>PASSWORD_RESET: 가입되지 않았거나 소셜 가입(비밀번호 없음) 계정이면 에러 없이 발송만 생략한다.
     * 응답 차이로 가입 여부를 알아낼 수 없게 하기 위함이다.
     */
    private boolean isSendTarget(String email, VerificationPurpose purpose) {
        return switch (purpose) {
            case SIGNUP -> {
                if (userRepository.existsByEmail(email)) {
                    throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
                }
                yield true;
            }
            case PASSWORD_RESET -> userRepository.findByEmail(email)
                .filter(user -> user.getProvider() == AuthProvider.LOCAL)
                .isPresent();
        };
    }

    private void send(String email, VerificationPurpose purpose, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject(purpose == VerificationPurpose.SIGNUP
            ? "[IEUM] 회원가입 인증코드"
            : "[IEUM] 비밀번호 재설정 인증코드");
        message.setText("인증코드: " + code + "\n\n"
            + "5분 안에 입력해주세요. 본인이 요청하지 않았다면 이 메일을 무시하세요.");
        try {
            mailSender.send(message);
        } catch (MailException e) {
            // 수신 주소와 코드는 로그에 남기지 않는다.
            log.error("[EmailVerification] 메일 발송 실패 — purpose: {}, cause: {}",
                purpose, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.MAIL_SEND_FAILED);
        }
    }

    private static String key(VerificationPurpose purpose, String email) {
        return purpose.name() + ":" + email;
    }
}
