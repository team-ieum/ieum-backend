# .claude/ 디렉토리

IEUM 백엔드 프로젝트의 Claude 커스텀 스킬 모음입니다.

## 스킬 목록

### 개발 패턴 스킬
| 파일 | 설명 |
|------|------|
| `skills/api-endpoint.md` | REST API 엔드포인트 생성 패턴 (Controller/Service/Repository/DTO) |
| `skills/jpa-entity.md` | JPA 엔티티 설계 패턴 (UUID PK, JSONB, AES-256 암호화) |
| `skills/exception-handling.md` | 공통 예외 처리 패턴 (IeumException + ErrorCode + ApiResponse) |
| `skills/ai-integration.md` | AI 모듈 연동 패턴 (Claude, OpenAI, 자격증명 처리) |
| `skills/new-module.md` | Gradle 서브모듈 추가 절차 |

### 검증 및 유지보수 스킬
| 파일 | 설명 |
|------|------|
| `skills/verify-implementation/SKILL.md` | 모든 verify 스킬을 순차 실행하여 통합 검증 보고서 생성 |
| `skills/manage-skills/SKILL.md` | 세션 변경사항 분석 → 검증 스킬 생성/업데이트 → CLAUDE.md 관리 |
