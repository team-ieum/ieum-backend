# Skill: REST API 엔드포인트 생성

## 목적
새로운 REST API 엔드포인트를 추가할 때 IEUM 프로젝트 컨벤션에 맞게 Controller / Service / Repository / DTO를 생성한다.

## API 설계 규칙
- Base URL: `/api/v1`
- 공통 응답 포맷: `{ "success": boolean, "data": object|null, "message": string }`
- Content-Type: `application/json`
- 인증: JWT (Authorization: Bearer {access_token})

## 공통 응답 래퍼

```java
// com.ieum.common.dto (또는 api 모듈)
@Getter
@Builder
@AllArgsConstructor
public class ApiResponse<T> {
    private final boolean success;
    private final T data;
    private final String message;

    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .message("ok")
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .data(null)
                .message(message)
                .build();
    }
}
```

## 레이어 구조 패턴

### 1. DTO (Request / Response)
```java
// com.ieum.api.<도메인>.dto
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class <Domain>Request {
    @NotBlank
    private String field;
}

@Getter
@Builder
public class <Domain>Response {
    private UUID id;  // IEUM은 UUID PK 사용
    private String field;

    public static <Domain>Response from(<Domain> entity) {
        return <Domain>Response.builder()
                .id(entity.getId())
                .field(entity.getField())
                .build();
    }
}
```

### 2. Controller
```java
// com.ieum.api.<도메인>.controller
@RestController
@RequestMapping("/api/v1/<도메인>")
@RequiredArgsConstructor
@Tag(name = "<도메인>", description = "<설명>")
public class <Domain>Controller {

    private final <Domain>Service <도메인>Service;

    @GetMapping("/{id}")
    @Operation(summary = "조회")
    public ResponseEntity<ApiResponse<<Domain>Response>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(<도메인>Service.get(id)));
    }

    @PostMapping
    @Operation(summary = "생성")
    public ResponseEntity<ApiResponse<<Domain>Response>> create(
            @RequestBody @Valid <Domain>Request request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(<도메인>Service.create(request)));
    }
}
```

### 3. Service
```java
// com.ieum.api.<도메인>.service  (또는 해당 모듈)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class <Domain>Service {

    private final <Domain>Repository <도메인>Repository;

    public <Domain>Response get(UUID id) {
        <Domain> entity = <도메인>Repository.findById(id)
                .orElseThrow(() -> new IeumException(ErrorCode.NOT_FOUND));
        return <Domain>Response.from(entity);
    }

    @Transactional
    public <Domain>Response create(<Domain>Request request) {
        <Domain> entity = <Domain>.builder()
                .field(request.getField())
                .build();
        return <Domain>Response.from(<도메인>Repository.save(entity));
    }
}
```

### 4. Repository
```java
// com.ieum.<모듈>.<도메인>.repository
public interface <Domain>Repository extends JpaRepository<<Domain>, UUID> {
    // 커스텀 쿼리 메서드
}
```

## 도메인별 API 엔드포인트 참고 (Notion API 스펙)

| 도메인 | 주요 엔드포인트 | 모듈 |
|--------|---------------|------|
| 인증 | `/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/google` | auth |
| 사용자 | `/users/me` (GET/PATCH) | api |
| 연동 계정 | `/connected-accounts`, `/connected-accounts/google`, `/connected-accounts/notion` | integration |
| 워크플로우 | `/workflows` (CRUD), `/workflows/{id}/activate`, `/workflows/{id}/deactivate` | workflow-core |
| 버전 | `/workflows/{id}/versions`, `/workflows/{id}/versions/{v}/restore` | workflow-core |
| 실행 | `/workflows/{id}/runs`, `/workflows/{id}/runs/{run_id}/retry` | workflow-core |
| Webhook | `/webhooks/{webhook_id}` (Public, 인증 불필요) | workflow-core |
| 알림 | `/notifications`, `/notifications/{id}/read`, `/notification-settings` | api |

## 체크리스트
- [ ] 응답은 `ApiResponse<T>` 공통 래퍼 사용 (`{ success, data, message }`)
- [ ] PK는 UUID 타입 사용 (Long 아님)
- [ ] DTO에 `@Valid` 검증 어노테이션 추가
- [ ] Service에 `@Transactional(readOnly = true)` 기본 적용, 쓰기는 메서드에 `@Transactional`
- [ ] Controller에 Swagger `@Tag`, `@Operation` 추가
- [ ] 예외는 common 모듈의 `IeumException` + `ErrorCode` 사용
- [ ] Response DTO에 `from()` 정적 팩토리 메서드 사용
- [ ] 보안 민감 데이터(토큰, API Key)는 응답에 절대 포함 금지
