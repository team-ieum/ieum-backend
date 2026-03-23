package com.ieum.auth.repository;

import com.ieum.auth.domain.OAuthAuthorizationCode;
import org.springframework.data.repository.CrudRepository;

public interface OAuthAuthorizationCodeRepository extends CrudRepository<OAuthAuthorizationCode, String> {
}