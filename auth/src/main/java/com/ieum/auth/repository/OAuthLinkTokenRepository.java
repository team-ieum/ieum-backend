package com.ieum.auth.repository;

import com.ieum.auth.domain.OAuthLinkToken;
import org.springframework.data.repository.CrudRepository;

public interface OAuthLinkTokenRepository extends CrudRepository<OAuthLinkToken, String> {
}
