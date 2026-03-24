# AI Module Context

## 역할
AI 프로바이더 연동 (Claude, OpenAI, Gemini), 크레덴셜(BYOK) 관리, 모델 레지스트리를 담당한다.

## BYOK (Bring Your Own Key) 모델
- 사용자가 직접 AI 프로바이더 API Key를 등록하는 구조
- Claude, OpenAI: API Key only (`credential_type: "api_key"`)
- Gemini: API Key + Google OAuth 재사용 둘 다 지원 (`credential_type: "api_key" | "oauth"`)
- API Key는 `AesEncryptor`로 AES-256 암호화 저장
- 조회 시 `key_hint`(앞 4자 + 마스킹)만 반환 — 원문 노출 금지
- 워크플로우 실행 시에만 서버 내부에서 복호화하여 사용

## 이미 구현된 클래스
- `Credential` 엔티티 — userId, provider, credentialType, encryptedKey, keyHint, displayName 등
- `AiProvider` enum — CLAUDE, OPENAI, GEMINI
- `CredentialType` enum — API_KEY, OAUTH
- `CredentialRepository` — JpaRepository (커맨드용)
- `CredentialQueryRepository` — QueryDSL 기반 조회용 (`@Repository` 클래스)
- `CredentialService` — CRUD + userId 기반 접근 제어
- `CredentialValidator` — 프로바이더별 API Key 유효성 실시간 검증 (실제 API 호출)
- `ProviderRegistry` — 지원 프로바이더/모델 목록 관리 (하드코딩된 레지스트리)
- `ModelInfo`, `ProviderInfo` — 프로바이더/모델 정보 VO
- `RestTemplateConfig` — 외부 API 호출용 RestTemplate Bean

## 향후 구현 (Phase 4)
- Provider 어댑터 패턴: `AiProviderAdapter` 인터페이스 → `ClaudeAdapter`, `OpenAiAdapter`, `GeminiAdapter`
- ReAct 실행 모드: tool_call → tool_result 루프 (최대 반복 횟수 제한)
- Simple 실행 모드: 단일 completion 호출
- `ExecutionGuard`: max_tokens, max_tool_calls 제한 적용
- `ToolInputValidator`: LLM self-correction 최대 2회 재시도
- 프롬프트 템플릿 관리: 사용자 정의 템플릿 CRUD

## 패키지 구조
```
com.ieum.ai
├── config/        # RestTemplateConfig
├── credential/
│   ├── domain/    # Credential, AiProvider, CredentialType
│   ├── repository/ # CredentialRepository, CredentialQueryRepository
│   └── service/   # CredentialService, CredentialValidator
└── provider/
    ├── model/     # ModelInfo, ProviderInfo
    └── service/   # ProviderRegistry
```

## 주의사항
- API Key 원문을 로그, 응답, node_runs의 JSONB에 절대 포함하지 않음
- CredentialValidator는 실제 프로바이더 API를 호출하므로 타임아웃 설정 필수
- RestTemplate은 프로바이더별 base URL과 헤더가 다름 — 어댑터 패턴으로 분리
