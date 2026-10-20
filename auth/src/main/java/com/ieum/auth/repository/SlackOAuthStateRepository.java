package com.ieum.auth.repository;

import com.ieum.auth.domain.SlackOAuthState;
import org.springframework.data.repository.CrudRepository;

public interface SlackOAuthStateRepository extends CrudRepository<SlackOAuthState, String> {
}
