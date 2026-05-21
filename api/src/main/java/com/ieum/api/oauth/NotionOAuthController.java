package com.ieum.api.oauth;

import com.ieum.auth.domain.NotionOAuthState;
import com.ieum.auth.repository.NotionOAuthStateRepository;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/notion/oauth2")
@RequiredArgsConstructor
public class NotionOAuthController implements NotionOAuthControllerDocs {

    private final NotionOAuthService notionOAuthService;
    private final NotionOAuthStateRepository notionOAuthStateRepository;

    @Value("${notion.oauth.auth-url}")
    private String notionAuthUrl;

    @Value("${notion.oauth.client-id}")
    private String notionClientId;

    @Value("${notion.oauth.redirect-uri}")
    private String notionRedirectUri;

    @Value("${notion.oauth.frontend-redirect-uri}")
    private String frontendRedirectUri;

    @GetMapping("/authorize")
    public ResponseEntity<Void> authorizeNotion(
        @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        String state = UUID.randomUUID().toString();
        notionOAuthStateRepository.save(
            NotionOAuthState.builder()
                .state(state)
                .userId(userDetails.getId())
                .ttl(300L)
                .build()
        );

        String authUrl = UriComponentsBuilder
            .fromUriString(notionAuthUrl)
            .queryParam("client_id", notionClientId)
            .queryParam("response_type", "code")
            .queryParam("owner", "user")
            .queryParam("redirect_uri", notionRedirectUri)
            .queryParam("state", state)
            .build().toUriString();

        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, authUrl)
            .build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> notionCallback(
        @RequestParam String code,
        @RequestParam String state
    ) {
        NotionOAuthState oAuthState = notionOAuthStateRepository.findById(state)
            .orElseThrow(() -> new CustomException(ErrorCode.INVALID_OAUTH_STATE));
        notionOAuthStateRepository.delete(oAuthState);  // 1회성 소비

        notionOAuthService.handleCallback(code, oAuthState.getUserId());

        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, frontendRedirectUri)
            .build();
    }
}
