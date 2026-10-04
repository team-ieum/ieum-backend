package com.ieum.api.integration.options;

import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

@Tag(name = "연동 옵션", description = "노드 설정 드롭다운 선택지 조회 (도구 필드 스키마의 optionsSource)")
@SecurityRequirement(name = "BearerAuth")
public interface IntegrationOptionControllerDocs {

    @Operation(
        summary = "드롭다운 선택지 조회",
        description = "도구 필드 스키마의 optionsSource({app}.{resource})에 해당하는 선택지를 조회합니다. "
            + "지원: google/spreadsheets, google/worksheets(spreadsheet_id 필수), google/calendars, "
            + "google/events(calendar_id 선택 — 비우면 기본 캘린더), google/files, google/folders. "
            + "nextCursor가 있으면 cursor로 다음 페이지를 조회하고, null이면 마지막 페이지입니다. "
            + "오류 code: GOOGLE_SCOPE_REQUIRED(403, Google 권한 증분 동의 필요 — 목록마다 필요한 scope가 다름), "
            + "ACCOUNT_NOT_CONNECTED(400), "
            + "AUTHENTICATION_REQUIRED(401, 재연동 필요), GOOGLE_RESOURCE_NOT_FOUND(404), "
            + "GOOGLE_API_UNAVAILABLE(503), INVALID_INPUT(400), NOT_FOUND(404, 알 수 없는 목록)")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<OptionPage>> getOptions(
        CustomUserDetails userDetails,
        @Parameter(description = "연동 앱", example = "google") String app,
        @Parameter(description = "리소스 종류", example = "worksheets") String resource,
        @Parameter(description = "cursor(선택)와 optionsInputs 필드 값. 예: spreadsheet_id=1Bxi…") Map<String, String> params);
}
