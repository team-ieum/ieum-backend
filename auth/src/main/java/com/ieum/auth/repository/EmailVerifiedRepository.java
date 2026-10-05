package com.ieum.auth.repository;

import com.ieum.auth.domain.EmailVerified;
import org.springframework.data.repository.CrudRepository;

public interface EmailVerifiedRepository extends CrudRepository<EmailVerified, String> {
}
