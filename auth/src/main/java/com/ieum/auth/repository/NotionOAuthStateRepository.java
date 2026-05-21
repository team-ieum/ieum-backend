package com.ieum.auth.repository;

import com.ieum.auth.domain.NotionOAuthState;
import org.springframework.data.repository.CrudRepository;

public interface NotionOAuthStateRepository extends CrudRepository<NotionOAuthState, String> {
}
