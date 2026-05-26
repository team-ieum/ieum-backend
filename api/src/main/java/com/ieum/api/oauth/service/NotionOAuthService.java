package com.ieum.api.oauth.service;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.domain.NotionOAuthState;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.repository.NotionOAuthStateRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptionService;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotionOAuthService {

    private static final long OAUTH_STATE_TTL_SECONDS = 300L;

    private final ConnectedAccountRepository connectedAccountRepository;
    private final NotionOAuthStateRepository notionOAuthStateRepository;
    private final AesEncryptionService aesEncryptionService;
    private final RestTemplate restTemplate;

    @Value("${notion.oauth.auth-url}")
    private String notionAuthUrl;

    @Value("${notion.oauth.token-url}")
    private String notionTokenUrl;

    @Value("${notion.oauth.client-id}")
    private String clientId;

    @Value("${notion.oauth.client-secret}")
    private String clientSecret;

    @Value("${notion.oauth.redirect-uri}")
    private String redirectUri;

    public String generateAuthUrl(UUID userId) {
        String state = UUID.randomUUID().toString();
        notionOAuthStateRepository.save(
            NotionOAuthState.builder()
                .state(state)
                .userId(userId)
                .ttl(OAUTH_STATE_TTL_SECONDS)
                .build()
        );

        return UriComponentsBuilder
            .fromUriString(notionAuthUrl)
            .queryParam("client_id", clientId)
            .queryParam("response_type", "code")
            .queryParam("owner", "user")
            .queryParam("redirect_uri", redirectUri)
            .queryParam("state", state)
            .build().toUriString();
    }

    @Transactional
    public void handleCallback(String code, UUID userId) {
        String accessToken = exchangeCodeForToken(code);
        String encryptedToken = aesEncryptionService.encrypt(accessToken);

        connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.NOTION)
            .ifPresentOrElse(
                account -> {
                    account.updateAccessToken(encryptedToken);
                    log.info("[NotionOAuthService] userId={} Notion access_token 갱신", userId);
                },
                () -> {
                    connectedAccountRepository.save(
                        ConnectedAccount.builder()
                            .userId(userId)
                            .provider(AuthProvider.NOTION)
                            .accessToken(encryptedToken)
                            .build()
                    );
                    log.info("[NotionOAuthService] userId={} Notion connected_account 신규 저장", userId);
                }
            );
    }

    private String exchangeCodeForToken(String code) {
        try {
            String credentials = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.AUTHORIZATION, "Basic " + credentials);

            Map<String, String> body = Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", redirectUri
            );

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = restTemplate.postForObject(
                notionTokenUrl, request, Map.class
            );

            if (responseBody == null) {
                log.error("[NotionOAuthService] token exchange 응답 body 없음");
                throw new CustomException(ErrorCode.TOKEN_REFRESH_FAILED);
            }

            String token = Optional.ofNullable(responseBody.get("access_token"))
                .map(Object::toString)
                .orElse("");
            if (token.isEmpty()) {
                log.error("[NotionOAuthService] 응답에 access_token 없음");
                throw new CustomException(ErrorCode.TOKEN_REFRESH_FAILED);
            }

            return token;
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[NotionOAuthService] token exchange 중 오류 발생", e);
            throw new CustomException(ErrorCode.TOKEN_REFRESH_FAILED);
        }
    }
}
