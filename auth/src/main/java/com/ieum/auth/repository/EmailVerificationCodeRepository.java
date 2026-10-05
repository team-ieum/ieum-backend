package com.ieum.auth.repository;

import com.ieum.auth.domain.EmailVerificationCode;
import org.springframework.data.repository.CrudRepository;

public interface EmailVerificationCodeRepository extends CrudRepository<EmailVerificationCode, String> {
}
