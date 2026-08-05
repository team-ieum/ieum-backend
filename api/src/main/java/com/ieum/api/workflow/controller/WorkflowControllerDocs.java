package com.ieum.api.workflow.controller;

import com.ieum.api.workflow.dto.CreateWorkflowRequest;
import com.ieum.api.workflow.dto.ExecuteWorkflowRequest;
import com.ieum.api.workflow.dto.UpdateWorkflowRequest;
import com.ieum.api.workflow.dto.WorkflowExecutionLogResponse;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.api.workflow.dto.WorkflowResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import com.ieum.common.dto.PageResponse;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.engine.event.ExecutionEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import reactor.core.publisher.Flux;

@Tag(name = "워크플로우", description = "워크플로우 생성·관리·실행")
@SecurityRequirement(name = "BearerAuth")
public interface WorkflowControllerDocs {

    @Operation(summary = "워크플로우 생성")
    @PreAuthorize("hasRole('USER')")
    @RequestBody(content = @Content(
        mediaType = "application/json",
        schema = @Schema(implementation = CreateWorkflowRequest.class),
        examples = {
            @ExampleObject(
                name = "MANUAL 트리거",
                summary = "수동 실행 — 이름 입력받아 인사 메시지 생성",
                value = """
                    {
                      "name": "MANUAL 인사 워크플로우",
                      "description": "수동으로 실행하면 이름을 받아 인사 메시지를 만든다",
                      "triggerType": "MANUAL",
                      "nodes": [
                        { "id": "node-trigger",   "type": "TRIGGER",   "label": "시작",
                          "description": "실행 버튼을 누르면 시작해요.",
                          "position": { "x": 40, "y": 120 }, "config": {} },
                        { "id": "node-transform", "type": "TRANSFORM", "label": "메시지 생성",
                          "description": "입력한 이름으로 인사말을 만들어요.",
                          "position": { "x": 420, "y": 120 },
                          "config": { "mappings": {
                            "message": "안녕하세요, {{nodes.node-trigger.output.name}}님!"
                          }}}
                      ],
                      "edges": [
                        { "source": "node-trigger", "target": "node-transform" }
                      ]
                    }"""
            ),
            @ExampleObject(
                name = "SCHEDULE 트리거",
                summary = "매일 오전 10시 자동 실행 — Quartz Cron",
                value = """
                    {
                      "name": "SCHEDULE 일일 보고 워크플로우",
                      "description": "매일 오전 10시에 외부 API를 호출해 데이터를 수집한다",
                      "triggerType": "SCHEDULE",
                      "cronExpression": "0 0 10 * * ?",
                      "nodes": [
                        { "id": "node-trigger",   "type": "TRIGGER", "label": "스케줄 시작",
                          "description": "매일 오전 10시에 자동으로 시작해요.",
                          "position": { "x": 40, "y": 120 }, "config": {} },
                        { "id": "node-http",      "type": "HTTP",    "label": "데이터 수집",
                          "description": "외부 서비스에서 오늘 데이터를 가져와요.",
                          "position": { "x": 420, "y": 120 },
                          "config": { "method": "GET", "url": "https://jsonplaceholder.typicode.com/todos/1", "headers": {} }},
                        { "id": "node-transform", "type": "TRANSFORM", "label": "결과 정리",
                          "description": "가져온 데이터에서 필요한 값만 골라내요.",
                          "position": { "x": 800, "y": 120 },
                          "config": { "mappings": {
                            "title":     "{{nodes.node-http.output.title}}",
                            "completed": "{{nodes.node-http.output.completed}}"
                          }}}
                      ],
                      "edges": [
                        { "source": "node-trigger", "target": "node-http" },
                        { "source": "node-http",    "target": "node-transform" }
                      ]
                    }"""
            ),
            @ExampleObject(
                name = "WEBHOOK 트리거",
                summary = "외부 시스템이 POST /webhooks/{id} 로 호출 — 인증 불필요",
                value = """
                    {
                      "name": "WEBHOOK 주문 알림 워크플로우",
                      "description": "외부 쇼핑몰에서 주문 이벤트 발생 시 호출",
                      "triggerType": "WEBHOOK",
                      "nodes": [
                        { "id": "node-trigger",   "type": "TRIGGER",   "label": "Webhook 수신",
                          "description": "쇼핑몰에서 주문이 들어오면 시작해요.",
                          "position": { "x": 40, "y": 120 }, "config": {} },
                        { "id": "node-condition", "type": "CONDITION", "label": "주문 금액 확인",
                          "description": "주문 금액이 5만원을 넘는지 확인해요.",
                          "position": { "x": 420, "y": 120 },
                          "config": { "left": "{{nodes.node-trigger.output.amount}}", "operator": "greaterThan", "right": "50000" }},
                        { "id": "node-vip",   "type": "TRANSFORM", "label": "VIP 처리",
                          "description": "고액 주문을 VIP 등급으로 표시해요.",
                          "position": { "x": 800, "y": 40 },
                          "config": { "mappings": { "grade": "VIP", "orderId": "{{nodes.node-trigger.output.orderId}}" }}},
                        { "id": "node-normal", "type": "TRANSFORM", "label": "일반 처리",
                          "description": "그 외 주문을 일반 등급으로 표시해요.",
                          "position": { "x": 800, "y": 320 },
                          "config": { "mappings": { "grade": "일반", "orderId": "{{nodes.node-trigger.output.orderId}}" }}}
                      ],
                      "edges": [
                        { "source": "node-trigger",   "target": "node-condition" },
                        { "source": "node-condition", "target": "node-vip",    "conditionType": "true" },
                        { "source": "node-condition", "target": "node-normal", "conditionType": "false" }
                      ]
                    }"""
            ),
            @ExampleObject(
                name = "AI 노드 (앱 동작 포함)",
                summary = "AI가 문의를 분류하고 Slack으로 알림 — 앱 동작은 AI 노드 + tools다",
                value = """
                    {
                      "name": "문의 분류 워크플로우",
                      "description": "들어온 문의를 AI가 분류하고 담당 채널에 알린다",
                      "triggerType": "WEBHOOK",
                      "nodes": [
                        { "id": "inquiry-trigger", "type": "TRIGGER", "label": "문의가 도착하면",
                          "description": "새 고객 문의가 들어오면 자동으로 시작해요.",
                          "position": { "x": 40, "y": 120 }, "config": {} },
                        { "id": "classify-inquiry", "type": "AI", "label": "문의 유형 나누기",
                          "description": "AI가 문의 내용을 읽고 알맞은 유형으로 나눠요.",
                          "position": { "x": 420, "y": 120 },
                          "config": {
                            "llmProvider": "GEMINI",
                            "model": "gemini-3.5-flash",
                            "credentialId": "550e8400-e29b-41d4-a716-446655440010",
                            "prompt": "문의 내용과 긴급도를 분류해 주세요.",
                            "agentType": "simple",
                            "tools": []
                          }},
                        { "id": "notify-team", "type": "AI", "label": "담당 채널에 알리기",
                          "description": "분류 결과를 담당 팀의 Slack 채널로 보내요.",
                          "position": { "x": 800, "y": 120 },
                          "config": {
                            "serviceType": "SLACK",
                            "llmProvider": "GEMINI",
                            "model": "gemini-3.5-flash",
                            "prompt": "다음 분류 결과를 슬랙으로 보내줘: {{nodes.classify-inquiry.output.output}}",
                            "agentType": "react",
                            "tools": [{ "name": "slack" }]
                          }}
                      ],
                      "edges": [
                        { "source": "inquiry-trigger",  "target": "classify-inquiry" },
                        { "source": "classify-inquiry", "target": "notify-team" }
                      ]
                    }"""
            ),
            @ExampleObject(
                name = "TRIGGER → TRANSFORM",
                summary = "변수 치환 기본 테스트",
                value = """
                    {
                      "name": "변수 치환 테스트",
                      "description": "triggerData 값을 TRANSFORM으로 가공",
                      "nodes": [
                        { "id": "node-trigger",   "type": "TRIGGER",   "label": "시작",
                          "description": "실행하면 시작해요.",
                          "position": { "x": 40, "y": 120 }, "config": {} },
                        { "id": "node-transform", "type": "TRANSFORM", "label": "메시지 가공",
                          "description": "받은 이름을 인사말로 바꿔요.",
                          "position": { "x": 420, "y": 120 },
                          "config": { "mappings": {
                            "greeting": "안녕하세요, {{nodes.node-trigger.output.name}}님!",
                            "original": "{{nodes.node-trigger.output.name}}"
                          }}}
                      ],
                      "edges": [
                        { "source": "node-trigger", "target": "node-transform" }
                      ]
                    }"""
            ),
            @ExampleObject(
                name = "TRIGGER → CONDITION → TRANSFORM 분기",
                summary = "score 값으로 합격/불합격 분기",
                value = """
                    {
                      "name": "CONDITION 분기 테스트",
                      "description": "score 값에 따라 pass/fail 분기",
                      "nodes": [
                        { "id": "node-trigger",   "type": "TRIGGER",   "label": "시작",
                          "description": "실행하면 시작해요.",
                          "position": { "x": 40, "y": 120 }, "config": {} },
                        { "id": "node-condition", "type": "CONDITION", "label": "점수 판정",
                          "description": "점수가 60점 이상인지 확인해요.",
                          "position": { "x": 420, "y": 120 },
                          "config": { "left": "{{nodes.node-trigger.output.score}}", "operator": "greaterThanOrEqual", "right": "60" }},
                        { "id": "node-pass", "type": "TRANSFORM", "label": "합격 처리",
                          "description": "60점 이상이면 합격으로 표시해요.",
                          "position": { "x": 800, "y": 40 },
                          "config": { "mappings": { "result": "합격", "score": "{{nodes.node-trigger.output.score}}" }}},
                        { "id": "node-fail", "type": "TRANSFORM", "label": "불합격 처리",
                          "description": "60점 미만이면 불합격으로 표시해요.",
                          "position": { "x": 800, "y": 320 },
                          "config": { "mappings": { "result": "불합격", "score": "{{nodes.node-trigger.output.score}}" }}}
                      ],
                      "edges": [
                        { "source": "node-trigger",   "target": "node-condition" },
                        { "source": "node-condition", "target": "node-pass", "conditionType": "true" },
                        { "source": "node-condition", "target": "node-fail", "conditionType": "false" }
                      ]
                    }"""
            ),
            @ExampleObject(
                name = "TRIGGER → HTTP → TRANSFORM",
                summary = "외부 API 호출 후 결과 가공",
                value = """
                    {
                      "name": "HTTP 호출 테스트",
                      "description": "공개 API 호출 후 결과 가공",
                      "nodes": [
                        { "id": "node-trigger",   "type": "TRIGGER",   "label": "시작",
                          "description": "실행하면 시작해요.",
                          "position": { "x": 40, "y": 120 }, "config": {} },
                        { "id": "node-http",      "type": "HTTP",      "label": "공개 API 호출",
                          "description": "외부 서비스에서 할 일 정보를 가져와요.",
                          "position": { "x": 420, "y": 120 },
                          "config": { "method": "GET", "url": "https://jsonplaceholder.typicode.com/todos/1", "headers": {} }},
                        { "id": "node-transform", "type": "TRANSFORM", "label": "결과 정리",
                          "description": "가져온 정보에서 제목과 완료 여부만 남겨요.",
                          "position": { "x": 800, "y": 120 },
                          "config": { "mappings": {
                            "todoTitle": "{{nodes.node-http.output.title}}",
                            "isDone":    "{{nodes.node-http.output.completed}}"
                          }}}
                      ],
                      "edges": [
                        { "source": "node-trigger", "target": "node-http" },
                        { "source": "node-http",    "target": "node-transform" }
                      ]
                    }"""
            )
        }
    ))
    ResponseEntity<ApiResponse<WorkflowResponse>> create(
            CustomUserDetails userDetails,
            @Valid CreateWorkflowRequest request);

    @Operation(summary = "워크플로우 목록 조회")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<PageResponse<WorkflowResponse>>> getList(
            CustomUserDetails userDetails,
            @Parameter(description = "커서 (페이지 번호)") String cursor,
            @Parameter(description = "페이지 크기") int size);

    @Operation(summary = "워크플로우 상세 조회")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<WorkflowResponse>> get(
            CustomUserDetails userDetails,
            @Parameter(description = "워크플로우 ID") UUID id);

    @Operation(summary = "워크플로우 수정 (새 버전 생성)")
    @PreAuthorize("hasRole('USER')")
    @RequestBody(content = @Content(
        mediaType = "application/json",
        schema = @Schema(implementation = UpdateWorkflowRequest.class),
        examples = {
            @ExampleObject(
                name = "이름·설명 수정 + 노드 추가",
                value = """
                    {
                      "name": "변수 치환 테스트 v2",
                      "description": "노드 하나 추가",
                      "nodes": [
                        { "id": "node-trigger",    "type": "TRIGGER",   "label": "시작",
                          "description": "실행하면 시작해요.",
                          "position": { "x": 40, "y": 120 }, "config": {} },
                        { "id": "node-transform1", "type": "TRANSFORM", "label": "1차 가공",
                          "description": "받은 이름을 그대로 넘겨요.",
                          "position": { "x": 420, "y": 120 },
                          "config": { "mappings": { "step1": "{{nodes.node-trigger.output.name}}" }}},
                        { "id": "node-transform2", "type": "TRANSFORM", "label": "2차 가공",
                          "description": "1차 결과에 안내 문구를 붙여요.",
                          "position": { "x": 800, "y": 120 },
                          "config": { "mappings": { "step2": "가공완료: {{nodes.node-transform1.output.step1}}" }}}
                      ],
                      "edges": [
                        { "source": "node-trigger",    "target": "node-transform1" },
                        { "source": "node-transform1", "target": "node-transform2" }
                      ]
                    }"""
            )
        }
    ))
    ResponseEntity<ApiResponse<WorkflowResponse>> update(
            CustomUserDetails userDetails,
            @Parameter(description = "워크플로우 ID") UUID id,
            @Valid UpdateWorkflowRequest request);

    @Operation(summary = "워크플로우 삭제")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<Void>> delete(
            CustomUserDetails userDetails,
            @Parameter(description = "워크플로우 ID") UUID id);

    @Operation(summary = "워크플로우 활성화")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<WorkflowResponse>> activate(
            CustomUserDetails userDetails,
            @Parameter(description = "워크플로우 ID") UUID id);

    @Operation(summary = "워크플로우 비활성화")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<WorkflowResponse>> deactivate(
            CustomUserDetails userDetails,
            @Parameter(description = "워크플로우 ID") UUID id);

    @Operation(summary = "워크플로우 수동 실행")
    @PreAuthorize("hasRole('USER')")
    @RequestBody(content = @Content(
        mediaType = "application/json",
        schema = @Schema(implementation = ExecuteWorkflowRequest.class),
        examples = {
            @ExampleObject(
                name = "이름 전달",
                summary = "TRIGGER → TRANSFORM 테스트용",
                value = """
                    { "triggerData": { "name": "홍길동" } }"""
            ),
            @ExampleObject(
                name = "점수 전달 (합격)",
                summary = "CONDITION 분기 테스트용 — score 85",
                value = """
                    { "triggerData": { "score": "85" } }"""
            ),
            @ExampleObject(
                name = "점수 전달 (불합격)",
                summary = "CONDITION 분기 테스트용 — score 45",
                value = """
                    { "triggerData": { "score": "45" } }"""
            ),
            @ExampleObject(
                name = "빈 triggerData",
                summary = "HTTP 호출 테스트용",
                value = """
                    {}"""
            )
        }
    ))
    ResponseEntity<ApiResponse<WorkflowExecutionResponse>> execute(
            CustomUserDetails userDetails,
            @Parameter(description = "워크플로우 ID") UUID id,
            ExecuteWorkflowRequest request);

    @Operation(summary = "실패 실행 재처리",
        description = "FAILED 상태의 실행을 같은 버전·같은 트리거 입력으로 다시 실행한다. "
            + "원 실행에서 이미 성공한 노드는 저장된 출력을 재사용하고 SKIPPED로 기록되며, "
            + "실패 지점부터 이어서 실행된다. 원 실행은 그대로 남고 새 실행 ID가 반환된다. "
            + "FAILED가 아닌 실행은 400.")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<WorkflowExecutionResponse>> retryExecution(
            CustomUserDetails userDetails,
            @Parameter(description = "재처리할 실행 ID") UUID executionId);

    @Operation(summary = "실행 목록 조회")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<PageResponse<WorkflowExecutionResponse>>> getExecutions(
            CustomUserDetails userDetails,
            @Parameter(description = "워크플로우 ID") UUID id,
            @Parameter(description = "실행 상태 필터 (PENDING/RUNNING/SUCCESS/FAILED)") ExecutionStatus status,
            @Parameter(description = "시작 시각 하한 (ISO-8601, 예: 2026-07-01T00:00:00)") LocalDateTime from,
            @Parameter(description = "시작 시각 상한 (ISO-8601)") LocalDateTime to,
            @Parameter(description = "커서 (페이지 번호)") String cursor,
            @Parameter(description = "페이지 크기 (1~100)") @Min(1) @Max(100) int size);

    @Operation(summary = "실행 로그 조회")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<List<WorkflowExecutionLogResponse>>> getExecutionLogs(
            CustomUserDetails userDetails,
            @Parameter(description = "워크플로우 ID") UUID id,
            @Parameter(description = "실행 ID") UUID executionId);

    @Operation(summary = "실행 진행 이벤트 SSE 스트림",
        description = "워크플로우 실행의 노드별 진행 상태(시작/완료/실패)를 SSE로 실시간 전송한다. "
            + "늦게 구독해도 진행 스냅샷을 먼저 재생한 뒤 라이브 이벤트를 잇는다.")
    @PreAuthorize("hasRole('USER')")
    Flux<ServerSentEvent<ExecutionEvent>> streamExecutionEvents(
            CustomUserDetails userDetails,
            @Parameter(description = "워크플로우 ID") UUID id,
            @Parameter(description = "실행 ID") UUID executionId);
}
