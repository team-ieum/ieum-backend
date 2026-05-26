package com.ieum.workflowcore.engine.executor;

import java.util.Optional;
import java.util.UUID;

/**
 * Port interface for retrieving the user's GitHub access token.
 *
 * <p>Implemented by DefaultGitHubTokenProvider in the api module.
 * workflow-core does not depend on auth, so the port is defined here
 * and wired by the api module adapter.
 *
 * <p>Unlike GoogleTokenProvider (which throws on missing account),
 * this returns Optional.empty() when the user has not connected GitHub.
 */
public interface GitHubTokenProvider {

    /**
     * Returns the user's valid GitHub access token (plaintext).
     * Automatically refreshes if the token is expired and a refresh token is available.
     *
     * @param userId the user ID to look up
     * @return valid access token, or empty if GitHub is not connected
     * @throws com.ieum.common.exception.CustomException AUTHENTICATION_REQUIRED — refresh token expired
     * @throws com.ieum.common.exception.CustomException TOKEN_REFRESH_FAILED — unexpected refresh error
     */
    Optional<String> getAccessToken(UUID userId);
}
