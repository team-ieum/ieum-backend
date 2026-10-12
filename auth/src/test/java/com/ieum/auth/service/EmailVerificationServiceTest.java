package com.ieum.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.EmailVerificationCode;
import com.ieum.auth.domain.EmailVerified;
import com.ieum.auth.domain.User;
import com.ieum.auth.domain.UserRole;
import com.ieum.auth.domain.VerificationPurpose;
import com.ieum.auth.repository.EmailVerificationCodeRepository;
import com.ieum.auth.repository.EmailVerifiedRepository;
import com.ieum.auth.repository.UserRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String SIGNUP_KEY = "SIGNUP:" + EMAIL;
    private static final String RESET_KEY = "PASSWORD_RESET:" + EMAIL;

    @Mock
    private EmailVerificationCodeRepository codeRepository;
    @Mock
    private EmailVerifiedRepository verifiedRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private JavaMailSender mailSender;

    @InjectMocks
    private EmailVerificationService service;

    private User user(AuthProvider provider) {
        return User.builder().email(EMAIL).name("홍길동").provider(provider).role(UserRole.ROLE_USER).build();
    }

    private EmailVerificationCode savedCode(String code, int attempts, long sentAgoMillis) {
        long now = System.currentTimeMillis();
        long sentAt = now - sentAgoMillis;
        return EmailVerificationCode.builder()
            .id(SIGNUP_KEY).code(code).attempts(attempts)
            .sentAt(sentAt).expiresAt(sentAt + 300_000).ttl(300L)
            .build();
    }

    // ---------------------------------------------------------------- sendCode

    @Test
    void 회원가입_코드_발송시_6자리_코드를_메일로_보내고_5분_TTL로_저장한다() {
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(codeRepository.findById(SIGNUP_KEY)).willReturn(Optional.empty());

        service.sendCode(EMAIL, VerificationPurpose.SIGNUP);

        ArgumentCaptor<EmailVerificationCode> saved = ArgumentCaptor.forClass(EmailVerificationCode.class);
        verify(codeRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(SIGNUP_KEY);
        assertThat(saved.getValue().getCode()).matches("\\d{6}");
        assertThat(saved.getValue().getTtl()).isEqualTo(300L);

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getTo()).containsExactly(EMAIL);
        assertThat(mail.getValue().getText()).contains(saved.getValue().getCode());
    }

    @Test
    void 이미_가입된_이메일은_회원가입_코드를_보내지_않는다() {
        given(userRepository.existsByEmail(EMAIL)).willReturn(true);

        assertThatThrownBy(() -> service.sendCode(EMAIL, VerificationPurpose.SIGNUP))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void 가입되지_않은_이메일의_재설정_요청은_에러없이_발송만_생략한다() {
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.empty());

        service.sendCode(EMAIL, VerificationPurpose.PASSWORD_RESET);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(codeRepository, never()).save(any());
    }

    @Test
    void 소셜_가입_계정의_재설정_요청은_에러없이_발송만_생략한다() {
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(user(AuthProvider.GOOGLE)));

        service.sendCode(EMAIL, VerificationPurpose.PASSWORD_RESET);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void LOCAL_계정은_재설정_코드를_발송한다() {
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(user(AuthProvider.LOCAL)));
        given(codeRepository.findById(RESET_KEY)).willReturn(Optional.empty());

        service.sendCode(EMAIL, VerificationPurpose.PASSWORD_RESET);

        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void 발송_30초_안에_재발송하면_429() {
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(codeRepository.findById(SIGNUP_KEY)).willReturn(Optional.of(savedCode("123456", 0, 10_000)));

        assertThatThrownBy(() -> service.sendCode(EMAIL, VerificationPurpose.SIGNUP))
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.VERIFICATION_RESEND_TOO_SOON);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void 발송_30초가_지나면_재발송할_수_있다() {
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(codeRepository.findById(SIGNUP_KEY)).willReturn(Optional.of(savedCode("123456", 0, 31_000)));

        service.sendCode(EMAIL, VerificationPurpose.SIGNUP);

        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void 메일_발송이_실패하면_코드를_저장하지_않는다() {
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(codeRepository.findById(SIGNUP_KEY)).willReturn(Optional.empty());
        willThrow(new MailSendException("smtp down")).given(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> service.sendCode(EMAIL, VerificationPurpose.SIGNUP))
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.MAIL_SEND_FAILED);
        verify(codeRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- verifyCode

    @Test
    void 코드가_일치하면_코드를_지우고_10분짜리_인증_표시를_남긴다() {
        given(codeRepository.findById(SIGNUP_KEY)).willReturn(Optional.of(savedCode("123456", 0, 0)));

        service.verifyCode(EMAIL, "123456", VerificationPurpose.SIGNUP);

        verify(codeRepository).deleteById(SIGNUP_KEY);
        ArgumentCaptor<EmailVerified> verified = ArgumentCaptor.forClass(EmailVerified.class);
        verify(verifiedRepository).save(verified.capture());
        assertThat(verified.getValue().getId()).isEqualTo(SIGNUP_KEY);
        assertThat(verified.getValue().getTtl()).isEqualTo(600L);
    }

    @Test
    void 코드가_틀리면_시도횟수를_올리고_TTL은_늘리지_않는다() {
        // 2분 전에 발송 — 남은 시간은 약 180초여야 한다
        given(codeRepository.findById(SIGNUP_KEY)).willReturn(Optional.of(savedCode("123456", 0, 120_000)));

        assertThatThrownBy(() -> service.verifyCode(EMAIL, "000000", VerificationPurpose.SIGNUP))
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.VERIFICATION_CODE_INVALID);

        ArgumentCaptor<EmailVerificationCode> saved = ArgumentCaptor.forClass(EmailVerificationCode.class);
        verify(codeRepository).save(saved.capture());
        assertThat(saved.getValue().getAttempts()).isEqualTo(1);
        assertThat(saved.getValue().getTtl()).isBetween(178L, 180L);
        verify(verifiedRepository, never()).save(any());
    }

    @Test
    void 다섯번째로_틀리면_코드를_폐기한다() {
        given(codeRepository.findById(SIGNUP_KEY)).willReturn(Optional.of(savedCode("123456", 4, 0)));

        assertThatThrownBy(() -> service.verifyCode(EMAIL, "000000", VerificationPurpose.SIGNUP))
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED);
        verify(codeRepository).deleteById(SIGNUP_KEY);
        verify(codeRepository, never()).save(any());
    }

    @Test
    void 코드가_없으면_만료로_응답한다() {
        given(codeRepository.findById(SIGNUP_KEY)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyCode(EMAIL, "123456", VerificationPurpose.SIGNUP))
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.VERIFICATION_CODE_EXPIRED);
    }

    // ---------------------------------------------------------------- consumeVerification

    @Test
    void 인증_표시가_있으면_소비하고_삭제한다() {
        given(verifiedRepository.existsById(RESET_KEY)).willReturn(true);

        service.consumeVerification(EMAIL, VerificationPurpose.PASSWORD_RESET);

        verify(verifiedRepository).deleteById(RESET_KEY);
    }

    @Test
    void 인증_표시가_없으면_EMAIL_NOT_VERIFIED() {
        given(verifiedRepository.existsById(SIGNUP_KEY)).willReturn(false);

        assertThatThrownBy(() -> service.consumeVerification(EMAIL, VerificationPurpose.SIGNUP))
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
    }

    @Test
    void 다른_용도의_인증으로는_소비할_수_없다() {
        // SIGNUP 인증만 있는 상태에서 PASSWORD_RESET으로 소비 시도 — 키가 달라 조회되지 않는다
        given(verifiedRepository.existsById(RESET_KEY)).willReturn(false);

        assertThatThrownBy(() -> service.consumeVerification(EMAIL, VerificationPurpose.PASSWORD_RESET))
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
        verify(verifiedRepository, never()).deleteById(SIGNUP_KEY);
    }
}
