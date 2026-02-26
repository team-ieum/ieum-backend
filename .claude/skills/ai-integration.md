# Skill: AI 모듈 연동 패턴

## 목적
`ai` 모듈에서 외부 AI API(Claude, OpenAI, Gemini)를 연동하는 일관된 패턴을 제공한다.
IEUM에서는 플랫폼 공용 키가 아닌 **사용자 본인 자격증명**으로 AI 노드를 실행한다.

## 자격증명 방식 (Notion 스펙 기반)

### credential_type별 처리
| credential_type | 참조 테이블 | 참조 필드 | 설명 |
|----------------|------------|----------|------|
| `api_key` | `user_ai_credentials` | `credential_id` | 사용자 직접 발급 API Key (AES-256 암호화 저장) |
| `oauth` | `connected_accounts` | `account_id` | OAuth로 AI 서비스 연동 (해당 서비스가 OAuth 지원 시) |

### MVP 기준 provider별 지원
| Provider | API Key | OAuth | 비고 |
|----------|---------|-------|------|
| Claude (Anthropic) | ✅ | ❌ | API Key만 지원 |
| OpenAI | ✅ | ❌ | API Key만 지원 |
| Gemini (Google) | ✅ | ✅ | API Key 또는 Google OAuth 재사용 가능 |

## AI 노드 타입 (Notion 노드 인터페이스 스펙)

### ai_summarize — 텍스트 요약
```json
{
  "config": {
    "credential_type": "api_key",
    "credential_id": "user_ai_credentials.id",
    "provider": "claude",
    "model": "claude-sonnet-4-6",
    "input_text": "${node_1.body}",
    "max_length": 200,
    "language": "ko"
  },
  "output": {
    "result": "string",
    "provider": "string",
    "model": "string"
  }
}
```

### ai_classify — 분류/태깅
```json
{
  "config": {
    "credential_type": "api_key",
    "credential_id": "user_ai_credentials.id",
    "provider": "claude",
    "model": "claude-haiku-4-5-20251001",
    "input_text": "${node_1.body}",
    "categories": ["문의", "주문", "불만", "기타"]
  },
  "output": {
    "category": "string",
    "confidence": "number",
    "provider": "string",
    "model": "string"
  }
}
```

### ai_extract — 데이터 추출
```json
{
  "config": {
    "credential_type": "api_key",
    "credential_id": "user_ai_credentials.id",
    "provider": "claude",
    "model": "claude-sonnet-4-6",
    "input_text": "${node_1.body}",
    "fields": {
      "이름": "발신자 이름",
      "전화번호": "연락처",
      "주문번호": "주문 번호"
    }
  },
  "output": { "이름": "string", "전화번호": "string", "주문번호": "string" }
}
```

## AI 클라이언트 인터페이스

```java
// com.ieum.ai.client
public interface AiClient {
    AiResponse execute(AiRequest request);
}
```

```java
@Getter
@Builder
public class AiRequest {
    private String provider;      // "claude", "openai", "gemini"
    private String model;
    private String systemPrompt;
    private String userMessage;
    private UUID credentialId;    // user_ai_credentials.id
    private String credentialType; // "api_key" or "oauth"
}

@Getter
@Builder
public class AiResponse {
    private String result;
    private String provider;
    private String model;
    private Integer inputTokens;
    private Integer outputTokens;
}
```

## user_ai_credentials 엔티티
```java
@Entity
@Table(name = "user_ai_credentials")
public class UserAiCredential extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String provider;  // 'claude', 'openai', 'gemini'

    @Column(nullable = false)
    private String credentialType;  // 'api_key' or 'oauth'

    @Column(columnDefinition = "TEXT")
    private String encryptedApiKey;  // ⚠️ AES-256 암호화 저장

    private String keyHint;  // 마스킹 표시용 (예: sk-...ab12)

    private UUID oauthAccountId;  // FK → connected_accounts (oauth일 때)

    @Column(nullable = false)
    private Boolean isValid;

    private LocalDateTime lastValidatedAt;
}
```

## 보안 규칙 (필수)
- API Key는 **AES-256 암호화** 후 DB 저장. 평문 저장 절대 금지
- 등록 후 `key_hint`만 조회 가능. 원문은 워크플로우 실행 시 서버 내부에서만 복호화
- `node_runs`의 `input`/`output` JSONB에 자격증명 포함 절대 금지
- config에 실제 토큰/키 값 절대 포함 금지 (credential_id로만 참조)

## 체크리스트
- [ ] API 키는 AES-256 암호화 후 저장 (환경변수로 암호화 키 관리)
- [ ] credential_type에 따라 올바른 테이블에서 자격증명 조회
- [ ] AI 호출 실패 시 재시도 로직 또는 fallback 처리
- [ ] node_runs 저장 시 자격증명 필터링 확인
- [ ] 타임아웃 설정 (WebClient에 timeout 추가)
- [ ] 토큰 사용량 모니터링 (inputTokens, outputTokens 기록)
