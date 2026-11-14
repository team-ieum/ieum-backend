# Backend DEV 배포 설정

DEV는 `https://green.kiacms.co.kr`을 사용합니다. 이 문서는 Secrets 등록과 환경설정 변경 방법을 설명합니다.
전체 배포·복구 절차는 [인프라 DEV 운영 문서](https://github.com/team-ieum/ieum-infra/blob/dev/docs/bluegreen-rollout.md)를 따릅니다.
MAIN 설정은 [MAIN 배포 설정](main-bluegreen-secrets.md)을 참고하세요.

## Secrets 등록 규칙

- 백엔드 저장소의 Actions Secrets 또는 이 저장소에 접근을 허용한 Organization Secrets에 등록합니다.
- 앱 설정은 `DEV_COM_` 접두사를 사용합니다. 예: `DEV_COM_REDIS_HOST` → `REDIS_HOST`.
- `DEV_`, `DEV_BLUE_`, `DEV_GREEN_`, `MAIN_`, `AWS_`, 접두사 없는 값은 앱 설정으로 수집하지 않습니다.
- DEV도 MAIN처럼 필요한 이름을 워크플로우와 수집기에 명시합니다. 전체 Secrets JSON은 받지 않습니다.
- 이름만 등록한 미지원 변수는 전달하지 않습니다. 새 변수는 아래 절차로 추가합니다.
- 현재 DEV job은 GitHub Environment를 지정하지 않으므로 Environment 전용 Secrets는 사용하지 않습니다.

## 새 앱 변수 추가 절차

이미 지원하는 변수의 **값만 변경**할 때는 Secrets를 갱신하고 새 DEV 배포를 실행합니다.
아래 절차는 **새 이름을 추가**하여 워크플로우·수집기·앱의 지원 범위를 늘릴 때 적용합니다.

`DEV_COM_NEW_OPTION` → `NEW_OPTION`은 **선택 변수의 구현 예시이며, 현재 지원하는 키가 아닙니다.**

- [ ] 앱에서 `NEW_OPTION`을 읽는 코드와 기본값을 준비합니다.
- [ ] [backend_env.py](../../scripts/bluegreen/backend_env.py)의 `DEV_OPTIONAL_KEYS`를 확장합니다.
  현재 `DEV_OPTIONAL_KEYS = MAIN_OPTIONAL_KEYS`이므로 DEV에만 추가하려면
  `DEV_OPTIONAL_KEYS = MAIN_OPTIONAL_KEYS + ('NEW_OPTION',)`으로 변경합니다. `DEV_SECRET_NAMES`는 이 목록에서 생성됩니다.
- [ ] [DEV 워크플로우](../../.github/workflows/backend-dev-server.yml)의
  `Deploy with prefixed GitHub Secrets` 단계 `env`에 다음 한 항목을 추가합니다.

```yaml
env:
  DEV_COM_NEW_OPTION: ${{ secrets.DEV_COM_NEW_OPTION }}
```

- [ ] 백엔드 저장소의 Secrets에 같은 이름을 등록하고, 아래 수집기 검증과 새 키의 전달·빈 값 테스트를 확인합니다.
- [ ] 변경이 포함된 새 DEV Backend 배포로 반영합니다. Secret 등록만으로 기존 컨테이너는 바뀌지 않습니다.

공유 `MAIN_OPTIONAL_KEYS`에 직접 추가한다면 [MAIN 워크플로우](../../.github/workflows/backend-main-server.yml)의
deploy·stage-bootstrap 단계에도 대응하는 `MAIN_COM_` 입력을 추가합니다. 공용 `REQUIRED_KEYS`를 바꿀 때도 MAIN 필수 검증에 미치는 영향을 확인합니다.

## 필수 앱 설정

아래 이름 앞에 `DEV_COM_`을 붙입니다. 기존 이름을 유지하려면 다음 절의 별칭을 사용할 수 있습니다.
34개 모두 값이 필요하며, 누락·빈 문자열·공백만 있는 값은 배포 전에 거부합니다.

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

## 기존 이름 매핑

아래 별칭에도 `DEV_COM_`을 붙입니다. 별칭과 앱 이름 중 하나만 등록하세요.
예를 들어 `DEV_COM_RDS_DB_URL`과 `DEV_COM_DB_URL`에 모두 값을 등록하면 충돌합니다.
GitHub가 미등록 이름에 제공하는 빈 문자열은 수집에서 제외하므로 기존 별칭만 등록해도 됩니다.

| Secret 접두사 뒤 이름 | 앱 환경변수 |
|---|---|
| `RDS_DB_URL` | `DB_URL` |
| `RDS_DB_USERNAME` | `DB_USERNAME` |
| `RDS_DB_PASSWORD` | `DB_PASSWORD` |
| `GH_APP_CLIENT_ID` | `GITHUB_APP_CLIENT_ID` |
| `GH_APP_CLIENT_SECRET` | `GITHUB_APP_CLIENT_SECRET` |
| `GH_APP_TOKEN_URL` | `GITHUB_APP_TOKEN_URL` |
| `GH_APP_REDIRECT_URI` | `GITHUB_APP_REDIRECT_URI` |
| `GH_APP_FRONTEND_REDIRECT_URI` | `GITHUB_APP_FRONTEND_REDIRECT_URI` |
| `GH_APP_WEBHOOK_SECRET` | `GITHUB_APP_WEBHOOK_SECRET` |

## 선택 설정과 빈 값

아래 이름 앞에도 `DEV_COM_`을 붙입니다. 기능을 사용한다면 해당 기능에 맞는 값을 등록하세요.

| 앱 환경변수 | 미등록·빈 문자열일 때 |
|---|---|
| `SPRING_PROFILES_ACTIVE` | 수집기가 `dev` 적용 |
| `GOOGLE_REDIRECT_URI` | 앱 기본값 `{baseUrl}/api/v1/oauth2/callback/{registrationId}` |
| `GITHUB_APP_WEBHOOK_SECRET` | 앱 기본값 빈 문자열 |
| `MAIL_HOST` | 앱 기본값 `smtp.gmail.com` |
| `MAIL_PORT` | 앱 기본값 `587` |
| `MAIL_USERNAME` | 앱 기본값 빈 문자열 |
| `MAIL_PASSWORD` | 앱 기본값 빈 문자열 |
| `OAUTH2_REDIRECT_URI` | 앱 기본값 `http://localhost:3000/oauth2/callback` |

다음 선택 항목도 지원합니다. 이름 앞에 `DEV_COM_`을 붙이고 사용하는 기능에 필요한 값만 등록합니다.

```text
APP_BASE_URL BETA_PLATFORM_KEY_ENABLED BETA_DAILY_CALL_QUOTA BETA_TOKEN_BUDGET BETA_ALLOWED_MODELS
GITHUB_APP_PRIVATE_KEY GITHUB_APP_SLUG DISCORD_OPS_WEBHOOK_URL GOOGLE_OAUTH_INCREMENTAL_AUTH
MVC_ASYNC_TIMEOUT_MS DB_POOL_MAX_SIZE DB_POOL_MIN_IDLE DB_POOL_CONNECTION_TIMEOUT_MS
DB_POOL_MAX_LIFETIME_MS DB_POOL_IDLE_TIMEOUT_MS AGENT_TIMEOUT_SECONDS
QUARTZ_THREAD_COUNT QUARTZ_THREAD_NAME_PREFIX WORKFLOW_EXECUTOR_CORE_POOL_SIZE
WORKFLOW_EXECUTOR_MAX_POOL_SIZE WORKFLOW_EXECUTOR_QUEUE_CAPACITY WORKFLOW_EXECUTION_PARALLELISM
GMAIL_TRIGGER_POLL_INTERVAL FORMS_TRIGGER_POLL_INTERVAL
```

미등록·빈 문자열인 선택값은 파일에서 생략하고 profile만 기본값을 넣습니다.
값의 앞뒤 공백은 자동으로 제거하지 않습니다. 값이 있는 별칭과 앱 이름을 함께 사용하면 거부합니다.
`GITHUB_APP_PRIVATE_KEY`는 실제 줄바꿈이 있는 PEM 대신 앱이 지원하는 한 줄 base64 PKCS#8 RSA 키를 사용합니다.

## 환경설정 파일과 배포 반영

1. 배포 단계에 명시한 Secrets를 읽어 권한 `0600`의 새 임시 후보 파일을 만듭니다.
2. 검증한 후보를 `--replace-env`로 전달해 새 색상의 사용자 설정을 전체 교체합니다.
3. 컨트롤러가 색상별 설정을 보존하고 Docker 컨테이너에 환경변수로 전달합니다.

색상별 파일은 `/home/kwung/deploy/bluegreen/runtime/{blue|green}/backend.env`입니다.
기존 서버 원본 `.env`나 실행 중인 컨테이너의 값으로 누락 설정을 보충하지 않습니다.
임시 파일은 작업 종료 시 정리하며, 비정상적인 러너 종료 후에는 잔존 파일을 확인합니다.
후보를 만든 뒤 배포 컨트롤러를 호출하기 전에 `DEV_COM_` 환경변수는 제거합니다.

- Backend를 새로 배포할 때 현재 Secrets를 읽습니다. Secrets 변경 후에는 새 workflow run을 시작하세요.
- Frontend·Agent만 배포하면 Backend의 기존 설정 스냅샷을 재사용합니다.
- 이전 배포로 되돌릴 때는 당시 이미지와 설정을 사용합니다.
- Secret 등록·수정만으로 실행 중인 컨테이너나 이전 배포 설정이 바뀌지는 않습니다.

## 운영 시 확인할 내용

- DB·Redis·MongoDB 주소는 실제 공용 데이터망에서 접근 가능한 이름을 사용합니다.
  컨테이너 이름이 일치한다면 `ieum-postgres`, `ieum-redis`, `ieum-mongo`를 사용할 수 있습니다.
- 기존 데이터를 계속 사용할 때 DB 계정과 JWT·AES 키를 임의로 바꾸지 않습니다.
- `AGENT_INTERNAL_TOKEN`은 같은 DEV Agent와 일치해야 합니다.
- CORS와 OAuth 콜백·완료 주소는 실제 DEV 주소 및 공급자 등록값과 대조합니다.
- `AGENT_SERVICE_URL`, 컨테이너·네트워크·IP, DDL·시간대·기동 제어 값은 컨트롤러가 관리합니다.
  해당 값을 일반 앱 Secret으로 덮어쓰지 않습니다.
- DB 스키마는 필요한 SQL을 수동 적용하고, 후보 Backend는 `ddl-auto=validate`로 확인합니다.
  앱 롤백은 DB 스키마를 되돌리지 않습니다.
- Java 버전 `21`과 이미지 이름 `ieum-backend-dev`는 DEV 워크플로우의 고정값입니다.

## 입력 오류와 점검

- 값은 한 줄 문자열로 등록합니다. 실제 줄바꿈은 거부하며 `$`, 따옴표, 공백, `=`는 그대로 보존합니다.
- 필수값 누락이나 동일 앱 변수로 매핑되는 두 이름에 모두 값이 있으면 배포를 중단시킵니다.
- 지원 목록 밖의 이름과 기존 전체 Secrets JSON은 읽지 않습니다. 누락값의 대체 원본도 아닙니다.
- 예약된 JVM·Spring 설정은 명시 입력 목록에 추가하지 않습니다. 전체 예약 목록은 수집기 코드를 확인하세요.
- 후보 생성 실패 시 기존 설정으로 대신 배포하지 않습니다. 로그의 오류 코드에 맞춰 Secrets를 수정합니다.
- 형식 검증만으로 DB·OAuth·메일 인증까지 확인되지는 않습니다. 변경한 기능을 별도로 점검하세요.
- 실제 값은 문서·로그·아티팩트에 넣지 않습니다. 서버 관리자와 Docker 관리자는 환경설정 값에 접근할 수 있습니다.

수집기 검증: `python3 -m unittest discover -s scripts/bluegreen -p 'test_*.py'`.
이 테스트는 모의 입력을 사용하며 실제 서버·DB 인증을 확인하지 않습니다.

구현 확인: [DEV 워크플로우](../../.github/workflows/backend-dev-server.yml) · [환경설정 수집기](../../scripts/bluegreen/backend_env.py).
