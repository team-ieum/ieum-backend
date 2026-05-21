package com.ieum.api.oauth;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptionService;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotionOAuthService {

    private static final String NOTION_TOKEN_ENDPOINT = "https://api.notion.com/v1/oauth/token";

    private final ConnectedAccountRepository connectedAccountRepository;
    private final AesEncryptionService aesEncryptionService;
    private final RestTemplate restTemplate;

    @Value("${notion.oauth.client-id}")
    private String clientId;

    @Value("${notion.oauth.client-secret}")
    private String clientSecret;

    @Value("${notion.oauth.redirect-uri}")
    private String redirectUri;

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
            ResponseEntity<Map> response = restTemplate.postForEntity(
                NOTION_TOKEN_ENDPOINT, request, Map.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("[NotionOAuthService] token exchange 실패: status={}", response.getStatusCode());
                throw new CustomException(ErrorCode.TOKEN_REFRESH_FAILED);
            }

            Object token = response.getBody().get("access_token");
            if (token == null) {
                log.error("[NotionOAuthService] 응답에 access_token 없음");
                throw new CustomException(ErrorCode.TOKEN_REFRESH_FAILED);
            }

            return token.toString();
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[NotionOAuthService] token exchange 중 오류 발생", e);
            throw new CustomException(ErrorCode.TOKEN_REFRESH_FAILED);
        }
    }
}
