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
            + "지원: google/spreadsheets, google/worksheets(spreadsheet_id 필수), google/calendars(읽기 전용 포함 — 이름 뒤 (읽기 전용)), "
            + "google/writable_calendars(일정을 쓸 수 있는 캘린더만 — accessRole writerWithoutPrivateAccess·writer·owner, 꼬리표 없음. 걸러진 페이지는 items가 비어도 nextCursor가 있을 수 있음), "
            + "google/events(calendar_id 선택 — 비우면 기본 캘린더, q 선택 — 검색어, 공백이면 무시. 지금 이후 일정만 시작순 20개씩), google/files, google/folders, "
            + "notion/pages, notion/databases(값은 data source id), "
            + "ai/models·ieum/credentials(llmProvider 필수 — CLAUDE·OPENAI·GEMINI), "
            + "ieum/webhooks(전체, HTTP 노드용)·slack/webhooks·discord/webhooks, ieum/mcp_servers"
            + "(웹훅·MCP는 활성 항목만, 모두 요청자 소유만). "
            + "github/owners(본인 login + 소속 org), github/repos(owner 필수 — owners의 값. 목록은 GitHub App 설치 범위 안만), "
            + "github/installed_repos(입력 없음, 값은 숫자 repo id 문자열·이름은 owner/name, 설치 범위 전체를 한 번에 — nextCursor 항상 null), "
            + "slack/channels(연결한 Slack 워크스페이스의 공개 채널, 보관 제외 — 값은 채널 ID·이름은 #이름. q 없으면 20개씩이고 "
            + "items가 비어도 nextCursor가 있을 수 있음. q 선택 — 이름 부분 일치(대소문자·앞의 # 무시), 최대 1,000개 중 20개, nextCursor 항상 null. "
            + "못 찾으면 items=[] — 채널 ID 직접 입력으로 안내), "
            + "nextCursor가 있으면 cursor로 다음 페이지를 조회하고, null이면 마지막 페이지입니다. "
            + "오류 code: GOOGLE_SCOPE_REQUIRED(403, Google 권한 증분 동의 필요 — scope_groups는 "
            + "spreadsheets·worksheets=sheets, files·folders=drive, calendars·writable_calendars·events=calendar. "
            + "sheets 그룹은 drive를 포함해 파일·폴더도 충족), "
            + "ACCOUNT_NOT_CONNECTED(400), SLACK_NOT_CONNECTED(400), "
            + "AUTHENTICATION_REQUIRED(401, 재연동 필요), GOOGLE_RESOURCE_NOT_FOUND(404), "
            + "GOOGLE_API_UNAVAILABLE(503), NOTION_API_UNAVAILABLE(503), GITHUB_API_UNAVAILABLE(503), SLACK_API_UNAVAILABLE(503), "
            + "INVALID_INPUT(400), NOT_FOUND(404, 알 수 없는 목록)")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<OptionPage>> getOptions(
        CustomUserDetails userDetails,
        @Parameter(description = "연동 앱", example = "google") String app,
        @Parameter(description = "리소스 종류", example = "worksheets") String resource,
        @Parameter(description = "cursor(선택), 검색어 q(선택 — 지원하는 목록만 읽음), optionsInputs 필드 값. 예: spreadsheet_id=1Bxi…, calendar_id=primary, q=회의") Map<String, String> params);
}
