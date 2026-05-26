package com.ieum.auth.repository;

import com.ieum.auth.domain.GitHubOAuthState;
import org.springframework.data.repository.CrudRepository;

public interface GitHubOAuthStateRepository extends CrudRepository<GitHubOAuthState, String> {
}
