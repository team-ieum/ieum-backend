package com.ieum.auth.repository;

import com.ieum.auth.domain.DiscordOAuthState;
import org.springframework.data.repository.CrudRepository;

public interface DiscordOAuthStateRepository extends CrudRepository<DiscordOAuthState, String> {
}
