package com.ieum.api.credential.controller;

import com.ieum.ai.credential.domain.AiProvider;
import com.ieum.ai.credential.domain.Credential;
import com.ieum.ai.credential.domain.CredentialType;
import com.ieum.ai.credential.service.CredentialService;
import com.ieum.api.credential.dto.CreateCredentialRequest;
import com.ieum.api.credential.dto.CredentialResponse;
import com.ieum.api.credential.dto.ValidateCredentialResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/credentials")
@RequiredArgsConstructor
public class CredentialController implements CredentialControllerDocs {

    private final CredentialService credentialService;

    @PostMapping
    public ResponseEntity<ApiResponse<CredentialResponse>> create(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid CreateCredentialRequest request) {

        AiProvider provider;
        CredentialType credentialType;
        try {
            provider = AiProvider.valueOf(request.getProvider());
            credentialType = CredentialType.valueOf(request.getCredentialType());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }

        Credential credential = credentialService.create(
                userDetails.getId(),
                provider,
                credentialType,
                request.getDisplayName(),
                request.getApiKey()
        );

        return ResponseEntity.status(201).body(ApiResponse.created(CredentialResponse.from(credential)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CredentialResponse>>> getList(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        List<CredentialResponse> responses = credentialService.getByUserId(userDetails.getId())
                .stream()
                .map(CredentialResponse::from)
                .toList();

        return ResponseEntity.ok(ApiResponse.ok(responses));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID id) {

        credentialService.delete(id, userDetails.getId());
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @PostMapping("/{id}/validate")
    public ResponseEntity<ApiResponse<ValidateCredentialResponse>> validate(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID id) {

        boolean isValid = credentialService.validateCredential(id, userDetails.getId());
        Credential credential = credentialService.getByIdAndUserId(id, userDetails.getId());

        ValidateCredentialResponse response = ValidateCredentialResponse.builder()
                .isValid(isValid)
                .provider(credential.getProvider().name())
                .build();

        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
