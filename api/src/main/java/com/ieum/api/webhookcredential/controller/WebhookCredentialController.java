package com.ieum.api.webhookcredential.controller;

import com.ieum.api.webhookcredential.domain.WebhookCredential;
import com.ieum.api.webhookcredential.dto.CreateWebhookCredentialRequest;
import com.ieum.api.webhookcredential.dto.WebhookCredentialResponse;
import com.ieum.api.webhookcredential.service.WebhookCredentialService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhook-credentials")
@RequiredArgsConstructor
public class WebhookCredentialController implements WebhookCredentialControllerDocs {

    private final WebhookCredentialService service;

    @PostMapping
    public ResponseEntity<ApiResponse<WebhookCredentialResponse>> create(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid CreateWebhookCredentialRequest request) {

        WebhookCredential credential = service.create(
                userDetails.getId(),
                request.provider(),
                request.displayName(),
                request.webhookUrl(),
                request.defaultChannel()
        );

        return ResponseEntity.status(201).body(ApiResponse.created(WebhookCredentialResponse.from(credential)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<WebhookCredentialResponse>>> getList(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        List<WebhookCredentialResponse> responses = service.getByUserId(userDetails.getId())
                .stream()
                .map(WebhookCredentialResponse::from)
                .toList();

        return ResponseEntity.ok(ApiResponse.ok(responses));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID id) {

        service.delete(id, userDetails.getId());
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
