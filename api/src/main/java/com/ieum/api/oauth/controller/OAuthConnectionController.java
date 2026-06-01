package com.ieum.api.oauth.controller;

import com.ieum.api.oauth.dto.OAuthConnectionResponse;
import com.ieum.api.oauth.service.OAuthConnectionService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/oauth/connections")
@RequiredArgsConstructor
public class OAuthConnectionController implements OAuthConnectionControllerDocs {

    private final OAuthConnectionService oauthConnectionService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<OAuthConnectionResponse>>> getConnections(
        @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        List<OAuthConnectionResponse> connections = oauthConnectionService.getConnections(userDetails.getId());
        return ResponseEntity.ok(ApiResponse.ok(connections));
    }
}
