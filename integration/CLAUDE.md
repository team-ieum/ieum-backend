# Integration Module Context

## 역할
외부 서비스 연동 (Google Workspace, Notion 등) 커넥터를 담당한다.

## 현재 상태
Phase 5에서 구현 예정. 현재는 빈 모듈 (`.gitkeep`만 존재)

## 핵심 설계 (구현 시 참고)

### 커넥터 아키텍처
- `ExternalServiceConnector` 인터페이스 → 각 서비스별 구현체
- Google Workspace: Gmail, Calendar, Drive, Sheets (4개 커넥터)
- Notion: Notion API 커넥터 (1개)

### OAuth 토큰 관리
- OAuth 토큰은 auth 모듈의 `connected_accounts` 테이블에서 조회
- AES-256 암호화 상태로 저장되어 있음 — `AesEncryptor`로 복호화 후 사용
- 토큰 만료 시 refresh_token으로 자동 갱신 → 갱신된 토큰 다시 암호화 저장
- 갱신 실패 시 `CustomException(ErrorCode.TOKEN_REFRESH_FAILED)` throw

### 연동 해제 규칙
- access_token / refresh_token 즉시 DELETE — 소프트 딜리트 금지
- 해당 connected_account를 참조하는 워크플로우가 있으면 `CustomException(ErrorCode.CREDENTIAL_IN_USE)` throw

## DB 테이블
- `connected_accounts` — OAuth 연동 토큰 (user_id, provider, encrypted_access_token, encrypted_refresh_token, token_expires_at)

## 패키지 구조 (예정)
```
com.ieum.integration
├── connector/     # ExternalServiceConnector 인터페이스
├── google/        # GmailConnector, CalendarConnector, DriveConnector, SheetsConnector
├── notion/        # NotionConnector
└── oauth/         # OAuthTokenManager (토큰 갱신/암복호화 로직)
```

## 주의사항
- 복호화된 토큰을 로그에 남기지 않음
- 외부 API 호출 시 타임아웃 설정 필수 (기본 30초)
- Rate Limit 대응: 429 응답 시 exponential backoff 재시도
- Redis 캐시 활용 가능 (토큰 갱신 빈도 최소화)
