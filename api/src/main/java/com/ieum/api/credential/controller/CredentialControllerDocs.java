package com.ieum.api.credential.controller;

import com.ieum.api.credential.dto.CreateCredentialRequest;
import com.ieum.api.credential.dto.CredentialResponse;
import com.ieum.api.credential.dto.ValidateCredentialResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.UUID;

@Tag(name = "크레덴셜", description = "AI 프로바이더 API 키 관리 (BYOK)")
@SecurityRequirement(name = "BearerAuth")
public interface CredentialControllerDocs {

    @Operation(summary = "크레덴셜 등록")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<CredentialResponse>> create(CustomUserDetails userDetails,
                                                           CreateCredentialRequest request);

    @Operation(summary = "크레덴셜 목록 조회")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<List<CredentialResponse>>> getList(CustomUserDetails userDetails);

    @Operation(summary = "크레덴셜 삭제")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<Void>> delete(CustomUserDetails userDetails,
                                             @Parameter(description = "크레덴셜 ID") UUID id);

    @Operation(summary = "크레덴셜 유효성 검증")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<ValidateCredentialResponse>> validate(CustomUserDetails userDetails,
                                                                     @Parameter(description = "크레덴셜 ID") UUID id);
}
