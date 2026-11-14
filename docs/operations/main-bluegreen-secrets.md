# Backend MAIN 배포 설정

MAIN은 `https://kiacms.co.kr`을 사용하며 DEV의 데이터·컨테이너·배포 상태와 분리합니다.
전체 설치·최초 활성화·복구 절차는 [인프라 MAIN 운영 문서](https://github.com/team-ieum/ieum-infra/blob/infra/IEUM-SRV-31/docs/main-bluegreen-rollout.md)를 따릅니다.
이 문서는 백엔드 Secrets와 실행 조건을 설명하며 실제 배포 완료 기록은 아닙니다.

## 실행 조건

| 작업 | 역할 | 서버 작업 조건 |
|---|---|---|
| `build` | 테스트·이미지 빌드·검증·업로드 | 서버 접속 없음 |
| `stage-bootstrap` | 최초 이미지·환경설정 보관, 앱 기동·전환 없음 | `main` 수동 실행, `MAIN_BOOTSTRAP_ENABLED=true` |
| `deploy` | MAIN Blue/Green 배포 | `main` push 또는 수동 deploy, `MAIN_DEPLOY_ENABLED=true` |

수동 기본 작업은 `build`입니다. `main`에서는 build가 실행되며, 작업 브랜치 `infra/IEUM-BE-123`에서는
수동 `build`만 허용합니다. GitHub `ubuntu-latest`에서 빌드하고 실제 배포는 다음 러너를 사용합니다.

- runner group: `ieum_main_server`
- labels: `self-hosted`, `linux`, `x64`, `ieum-backend`, `main`, `deploy`
- 컨트롤러: `/home/kwung/deploy/bluegreen-main/bluegreen.py`
- helper: `/usr/local/sbin/ieum-main-bluegreen-nginx`
- 설치 검증 Variables: `MAIN_BLUEGREEN_CONTROLLER_SHA256`, `MAIN_BLUEGREEN_HELPER_SHA256`

배포 준비 중에는 `MAIN_DEPLOY_ENABLED=false`를 유지합니다. 최초 준비·전환·기능 검증을 마친 뒤
bootstrap 허용을 끄고 일반 배포를 활성화하는 순서는 인프라 운영 문서를 따릅니다.

## Secrets 등록 규칙

- 기존 저장소의 Actions Secrets 등록 위치를 유지하며 앱 설정에는 `MAIN_COM_`을 붙입니다.
- MAIN은 필요한 Secret 이름을 워크플로우와 수집기에 **명시**합니다. 전체 Secrets JSON을 사용하지 않습니다.
- 새 앱 변수는 Secret 등록과 함께 `backend_env.py`의 `MAIN_SECRET_NAMES` 구성 목록,
  워크플로우의 deploy·stage-bootstrap 단계 `env`에 추가합니다. 새 필수값이면 필수 검증도 갱신합니다.
- DEV의 자동 수집 방식은 유지합니다. MAIN 변수는 등록만으로 자동 추가되지 않습니다.
- `DEV_`, `AWS_`, 접두사 없는 이름, 기존 서버 `.env`를 누락값의 대체 원본으로 사용하지 않습니다.

### 빌드 전용

| Secret | 설정 |
|---|---|
| `MAIN_COM_JAVA_VERSION` | Java 21 계열. 런타임 이미지도 Java 21 사용 |
| `MAIN_COM_IMAGE_NAME` | `main` 구분자가 있는 이미지 이름. 예: `ieum-backend-main` |

빌드용 값은 앱 `.env`에 넣지 않습니다. 빌드 job은 앱 Secrets를 받지 않습니다.

### 필수 앱 설정

아래 34개 이름 앞에 `MAIN_COM_`을 붙입니다. 누락·빈 문자열·공백만 있는 값은 거부합니다.

```text
DB_URL DB_USERNAME DB_PASSWORD
REDIS_HOST REDIS_PORT MONGO_URI
GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET CORS_ALLOWED_ORIGINS
JWT_SECRET JWT_EXPIRATION JWT_REFRESH_EXPIRATION AES_SECRET_KEY
NOTION_CLIENT_ID NOTION_CLIENT_SECRET NOTION_AUTH_URL NOTION_TOKEN_URL
NOTION_REDIRECT_URI NOTION_FRONTEND_REDIRECT_URI
GITHUB_APP_CLIENT_ID GITHUB_APP_CLIENT_SECRET GITHUB_APP_TOKEN_URL
GITHUB_APP_REDIRECT_URI GITHUB_APP_FRONTEND_REDIRECT_URI
SLACK_CLIENT_ID SLACK_CLIENT_SECRET SLACK_REDIRECT_URI SLACK_FRONTEND_REDIRECT_URI
DISCORD_CLIENT_ID DISCORD_CLIENT_SECRET DISCORD_BOT_TOKEN
DISCORD_REDIRECT_URI DISCORD_FRONTEND_REDIRECT_URI AGENT_INTERNAL_TOKEN
```

### 기존 이름 매핑

아래 별칭에도 `MAIN_COM_`을 붙일 수 있습니다. 앱 이름과 별칭 중 하나만 값이 있도록 등록하세요.
예: `MAIN_COM_RDS_DB_URL` → `DB_URL`.

| Secret 접두사 뒤 이름 | 앱 환경변수 |
|---|---|
| `RDS_DB_URL` / `RDS_DB_USERNAME` / `RDS_DB_PASSWORD` | `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` |
| `GH_APP_CLIENT_ID` / `GH_APP_CLIENT_SECRET` | `GITHUB_APP_CLIENT_ID` / `GITHUB_APP_CLIENT_SECRET` |
| `GH_APP_TOKEN_URL` / `GH_APP_REDIRECT_URI` | `GITHUB_APP_TOKEN_URL` / `GITHUB_APP_REDIRECT_URI` |
| `GH_APP_FRONTEND_REDIRECT_URI` / `GH_APP_WEBHOOK_SECRET` | `GITHUB_APP_FRONTEND_REDIRECT_URI` / `GITHUB_APP_WEBHOOK_SECRET` |

### 선택 앱 설정

아래 이름 앞에 `MAIN_COM_`을 붙입니다. 사용하는 기능에 필요한 항목만 등록합니다.
미등록·빈 문자열은 파일에서 생략하여 앱 기본값을 유지합니다.

```text
SPRING_PROFILES_ACTIVE
GOOGLE_REDIRECT_URI GITHUB_APP_WEBHOOK_SECRET OAUTH2_REDIRECT_URI
MAIL_HOST MAIL_PORT MAIL_USERNAME MAIL_PASSWORD
APP_BASE_URL BETA_PLATFORM_KEY_ENABLED BETA_DAILY_CALL_QUOTA BETA_TOKEN_BUDGET BETA_ALLOWED_MODELS
GITHUB_APP_PRIVATE_KEY GITHUB_APP_SLUG DISCORD_OPS_WEBHOOK_URL GOOGLE_OAUTH_INCREMENTAL_AUTH
MVC_ASYNC_TIMEOUT_MS DB_POOL_MAX_SIZE DB_POOL_MIN_IDLE DB_POOL_CONNECTION_TIMEOUT_MS
DB_POOL_MAX_LIFETIME_MS DB_POOL_IDLE_TIMEOUT_MS AGENT_TIMEOUT_SECONDS
QUARTZ_THREAD_COUNT QUARTZ_THREAD_NAME_PREFIX WORKFLOW_EXECUTOR_CORE_POOL_SIZE
WORKFLOW_EXECUTOR_MAX_POOL_SIZE WORKFLOW_EXECUTOR_QUEUE_CAPACITY WORKFLOW_EXECUTION_PARALLELISM
GMAIL_TRIGGER_POLL_INTERVAL FORMS_TRIGGER_POLL_INTERVAL
```

- `SPRING_PROFILES_ACTIVE`는 미등록·빈 값이면 `main`이며 다른 값은 거부합니다.
- 메일·Google 로그인 기본값은 [DEV의 선택 설정 표](bluegreen-secrets.md#선택-설정과-빈-값)를 참고하되,
  MAIN에서 사용할 OAuth 콜백·완료 주소는 공급자 등록값과 실제 MAIN 화면에 맞게 지정합니다.
- `GITHUB_APP_PRIVATE_KEY`는 실제 줄바꿈이 있는 PEM 대신 앱이 지원하는 한 줄 base64 PKCS#8 RSA 키를 사용합니다.

## 환경설정과 이미지 반영

1. 빌드한 이미지의 태그·SHA-256·플랫폼을 검증하고 같은 실행의 결과물만 서버에 전달합니다.
2. 서버 작업 단계에서 명시된 MAIN Secrets로 권한 `0600`의 새 후보 파일을 생성합니다.
3. 컨트롤러에 검증된 이미지 ID와 후보 파일을 전달합니다. 기존 사용자 설정에 덧붙이지 않고 전체 교체합니다.

색상별 파일은 `/home/kwung/deploy/bluegreen-main/runtime/{blue|green}/backend.env`입니다.
MAIN Blue·Green은 같은 설정 원본을 읽지만 각 배포의 스냅샷을 별도로 보존합니다.
이전 배포로 되돌릴 때는 당시 이미지와 설정을 사용합니다. Frontend·Agent만 배포하면 Backend의 기존 스냅샷을 재사용합니다.
Secret 수정만으로 실행 중인 컨테이너나 이전 배포 설정이 바뀌지는 않습니다. 변경 후에는 새 run을 시작하세요.
임시 후보 파일은 작업 종료 시 정리하며, 비정상적인 러너 종료 후에는 잔존 파일을 확인합니다.

## 운영 시 확인할 내용

- DB·Redis·MongoDB는 MAIN 전용 서비스를 가리켜야 합니다.
- JWT·AES 키와 내부 토큰은 MAIN Blue·Green끼리 같고 DEV와 분리합니다.
  기존 암호화 데이터를 이관한다면 AES 키 변경에 따른 복호화 문제를 먼저 검토합니다.
- `AGENT_INTERNAL_TOKEN`은 MAIN Agent와 일치해야 합니다.
- CORS는 `https://kiacms.co.kr` 기준으로 구성하고 OAuth·메일·웹훅은 실제 기능을 확인합니다.
- `AGENT_SERVICE_URL`과 색상별 주소·컨테이너·배포 제어 설정은 컨트롤러가 관리합니다.
- 업무·Quartz 스키마는 사전에 수동 준비합니다. 컨트롤러가 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`를 강제하며
  자동 migration이나 사용자 Secret을 통한 DDL 설정 변경은 허용하지 않습니다. 앱 롤백은 DB를 되돌리지 않습니다.

## 입력 오류와 검증

- 값은 한 줄 문자열입니다. 실제 줄바꿈은 거부하며 공백·달러·따옴표·등호는 자동 확장하거나 제거하지 않습니다.
- 비어 있지 않은 별칭과 앱 이름을 함께 등록하면 충돌합니다. 필수값 누락·예약 설정도 배포를 중단시킵니다.
- 실제 값은 문서·로그·아티팩트에 넣지 않습니다. 서버 관리자와 Docker 관리자는 환경설정 값에 접근할 수 있습니다.
- 단위 테스트는 형식·분리 규칙을 검사합니다. 실제 DB·OAuth 인증과 트래픽 전환은 별도로 확인합니다.

수집기 검증: `python3 -m unittest discover -s scripts/bluegreen -p 'test_*.py'`.

구현 확인: [MAIN 워크플로우](../../.github/workflows/backend-main-server.yml) · [환경설정 수집기](../../scripts/bluegreen/backend_env.py).
