# Main 블루그린 배포 설정

이 문서는 운영 도메인 `https://kiacms.co.kr`의 로컬 구현 계약이다. DEV 도메인
`https://green.kiacms.co.kr`과 데이터·컨트롤러 상태·앱 컨테이너를 분리한다.
현재 변경만으로 서버가 설치되거나 배포되지는 않는다.

## 실행 조건

- Main workflow는 `main` push 또는 수동 실행에서만 선택된다.
- build는 배포 flag 없이 main에서 실행한다. 작업 브랜치 `infra/IEUM-BE-123`는
  수동 `operation=build`일 때만 GitHub 빌드를 허용하며 main 배포와 concurrency를 분리한다.
- 서버의 deploy·stage-bootstrap job은 계속 `github.ref == 'refs/heads/main'`을 요구한다.
- 수동 operation은 `build`(기본), `stage-bootstrap`, `deploy`다. 기본 수동 실행은 배포하지 않는다.
- 실제 deploy job은 `MAIN_DEPLOY_ENABLED == 'true'`이며 push 또는 명시적 deploy일 때만 실행한다.
- bootstrap 입력 저장 job은 수동 stage-bootstrap과 `MAIN_BOOTSTRAP_ENABLED == 'true'`일 때만 실행한다.
- build/test/image 생성은 `ubuntu-latest`에서 수행한다. 운영 DB나 DEV DB를 테스트에 사용하지 않는다.
- 배포 job은 group `ieum_main_server`, labels
  `[self-hosted, linux, x64, ieum-backend, main, deploy]`인 러너를 요구한다.
- 배포 job은 `IEUM_DEPLOY_ENVIRONMENT=main`으로
  `/home/kwung/deploy/bluegreen-main/bluegreen.py`를 호출한다.
- Nginx helper는 `/usr/local/sbin/ieum-main-bluegreen-nginx`이다.
- Repository Variables `MAIN_BLUEGREEN_CONTROLLER_SHA256`와
  `MAIN_BLUEGREEN_HELPER_SHA256`를 실제 승인·설치한 파일의 SHA-256으로 등록한다.
  DEV의 SHA 변수·제어 파일·잠금 파일·상태 파일을 덮어쓰지 않는다.

## 이미지 전달과 설정 경계

build job은 앱 Secrets 전체 JSON을 받지 않는다. 빌드 메타데이터 두 개만 읽는다.
테스트 후 커밋 SHA 태그의 이미지를 생성한다. GitHub 러너에서 `docker save` 아카이브를
검증·재로드하는 검사까지 통과하면 아카이브 한 파일만 업로드한다. 이 검사는 서버에 접속하지 않는다.
이미지 이름에는 `main` 구분자가 있어야 하고 `dev` 구분자는 허용하지 않는다.
`latest` 같은 mutable tag로 전달하지 않는다.

배포 job은 같은 run의 정확한 artifact ID로 다운로드하고, build job이 기록한 아카이브
SHA-256과 단일 MAIN 태그·linux/amd64 플랫폼을 확인한 뒤 Docker에 로드한다.
classic Docker의 config ID와 containerd의 manifest/index ID를 구분해 참조 연결을 검증한다.
저장 방식 변환으로 ID가 달라지면 실제 로드한 이미지를 확인하고, 필요한 경우 임시 tar로
다시 저장해 config 해시가 같은지 검증한 뒤 정리한다. deploy와 stage-bootstrap은 모두
검증 단계가 출력한 서버의 실제 `sha256:...` image ID를 컨트롤러에 전달한다.
앱 환경설정 파일·Secrets JSON은 artifact에 포함하지 않는다.

앱 Secrets는 배포·bootstrap 설정 준비 단계의 `env:`에 필요한 `secrets.MAIN_COM_*`를
각각 명시한다. 전체 Secrets JSON을 전달하지 않으며 빌드 단계는 앱 Secrets를 받지 않는다.
수집기는 `MAIN_SECRET_NAMES`에 선언된 개별 환경변수만 읽고 권한 0600인 새 후보 파일을
만든다. 컨트롤러 호출 전 `MAIN_COM_*` 환경변수를 unset하며 설정은 후보 파일로 전달한다.
정상 종료·처리 가능한 실패·INT·TERM에서 후보 파일을 정리한다. SIGKILL·호스트 장애에서
trap 실행은 보장되지 않으므로 운영자 점검이 필요하다.

이 변경은 기존 GitHub Secret 이름과 저장 위치를 유지한다. GitHub Environment 생성이나
Secrets/Variables 이동은 포함하지 않는다. 원문·값·파일 내용을 로그나 artifact로 출력하지 않는다.
Docker 관리자 및 서버 snapshot 파일에는 평문 설정 접근이 가능하다.

## 수집 규칙

- CLI: `python3 scripts/bluegreen/backend_env.py PRIVATE_EMPTY_FILE --environment main`
- `--environment`를 생략하면 기존 `dev` 동작과 `IEUM_BACKEND_SECRETS_JSON` 입력을 유지한다.
  Main CLI는 이 JSON을 사용하거나 fallback으로 읽지 않는다.
- Main은 `MAIN_COM_` 접두사를 한 번만 제거한다. `DEV_COM_`, `DEV_`, `AWS_`,
  접두사 없는 값, `MAIN_BLUE_`, `MAIN_GREEN_`을 앱 설정 fallback으로 사용하지 않는다.
- 기존 별칭은 Main에서도 유지한다. 별칭과 직접 이름이 모두 비어 있지 않으면 충돌이다.
  GitHub가 미등록 Secret과 빈 Secret을 모두 빈 문자열로 전달하므로 Main은 빈 입력을
  생략한다. 필수값이 빠지면 중단하고 선택 설정은 앱 기본값을 유지한다. 선택 설정을 빈
  문자열로 강제로 덮어쓰는 용도로 사용할 수 없다. 공백만 있는 값은 자동 trim하지 않는다.
- 입력은 단일 행 literal이다. 공백·달러·따옴표·등호는 자동 확장하지 않는다.
- 후보는 기존 서버 .env 또는 현재 컨테이너의 설정과 합치지 않고 전체 교체한다.
- 새 Secret 등록만으로 Main `.env`에 추가되지 않는다. 새 앱 키는 `backend_env.py`의
  `MAIN_SECRET_NAMES` 구성 목록과 workflow의 deploy·stage-bootstrap `env:` 두 곳에
  명시해야 한다. 앱이 해당 키를 읽어야 하며 새 필수 검증도 필요하면 함께 추가한다.
- Blue·Green의 컨테이너·내부 주소·배포 제어 설정은 Main 컨트롤러 소유다.
  이를 일반 앱 Secrets로 덮어쓰는 예약 이름과 동등한 underscore 변형을 거부한다.
- Main Blue·Green은 Main 공통 설정을 배포 시 읽지만 각 색상 snapshot을 독립 보존한다.
  retained release rollback은 당시 이미지와 설정을 사용한다.
- Secret 수정만으로 실행 중인 컨테이너나 retained snapshot이 바뀌지 않는다.

## 초기 운영 적용

1. Main 전용 앱망·데이터망, DB·Redis·MongoDB 및 컨트롤러·helper를 준비한다.
   DEV 서비스의 데이터나 상태를 그대로 공유하지 않는다. 실제 설치 SHA를 Main Variables에
   등록하고 Main 러너 접근·라벨을 staging 전에 확인한다.
2. main의 build 또는 수동 기본 build로 검증된 최초 이미지 artifact와 job summary의 identity를 확보한다.
   `MAIN_BOOTSTRAP_ENABLED=true`를 잠시 허용하고 각 앱에서 stage-bootstrap을 명시 실행하면
   검증한 이미지와 MAIN_COM_ 후보 설정을 Main 컨트롤러의 bootstrap 저장소에 보관한다.
   이 단계는 컨테이너 시작·트래픽 전환을 수행하지 않으며 활성 Main이 있으면 거부된다.
   앱 설정 파일은 artifact에 올리지 않으며 GitHub API에서 Secret 평문을 다시 조회하지 않는다.
3. 세 앱의 bootstrap manifest·이미지·설정을 함께 검증한 후 init/prepare/switch로 첫 활성 환경을 만든다.
   staging이 끝나면 `MAIN_BOOTSTRAP_ENABLED`를 끈다.
   앱 workflow가 독자적으로 DB를 생성하거나 migration을 실행하지 않는다.
4. 도메인/TLS, OAuth callback, DB·Redis·MongoDB 연결, 내부 토큰과 같은 색상의 호출 경로를 확인한다.
5. 운영자가 준비 완료를 확인한 뒤에만 `MAIN_DEPLOY_ENABLED=true`를 설정한다.

관리 flag를 켜거나 이 workflow를 머지하는 작업은 서버 bootstrap 완료의 증거가 아니다.
실제 drain·트래픽 전환·rollback과 진행 중 요청 처리는 별도로 검증한다.

## 로컬 검증

```bash
python3 -m unittest discover -s scripts/bluegreen -p 'test_*.py'
```

테스트는 합성 설정·가짜 Docker·가짜 컨트롤러와 임시 파일을 사용한다. 실제 Secrets,
서버·Docker daemon·운영 DB에 접속하는 테스트로 표현하지 않는다.

## Backend Main Secrets

빌드 전용이며 runtime 후보에서 제외한다.

| Secret | 조건 |
|---|---|
| `MAIN_COM_JAVA_VERSION` | Java 21 계열 버전. setup-java에 사용, runtime 이미지 major도 21 |
| `MAIN_COM_IMAGE_NAME` | Main 구분자가 있는 별도 이미지명, 예: ieum-backend-main |

앱 필수값은 DEV와 동일한 34개다. 실제 이름은 `MAIN_COM_` + 아래 앱 키이며,
기존 `RDS_DB_*` 3개 및 `GH_APP_*` 6개 별칭도 지원한다.

```
DB_URL DB_USERNAME DB_PASSWORD REDIS_HOST REDIS_PORT MONGO_URI
GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET CORS_ALLOWED_ORIGINS
JWT_SECRET JWT_EXPIRATION JWT_REFRESH_EXPIRATION AES_SECRET_KEY
NOTION_CLIENT_ID NOTION_CLIENT_SECRET NOTION_AUTH_URL NOTION_TOKEN_URL
NOTION_REDIRECT_URI NOTION_FRONTEND_REDIRECT_URI
GITHUB_APP_CLIENT_ID GITHUB_APP_CLIENT_SECRET GITHUB_APP_TOKEN_URL
GITHUB_APP_REDIRECT_URI GITHUB_APP_FRONTEND_REDIRECT_URI
SLACK_CLIENT_ID SLACK_CLIENT_SECRET SLACK_REDIRECT_URI SLACK_FRONTEND_REDIRECT_URI
DISCORD_CLIENT_ID DISCORD_CLIENT_SECRET DISCORD_BOT_TOKEN DISCORD_REDIRECT_URI
DISCORD_FRONTEND_REDIRECT_URI AGENT_INTERNAL_TOKEN
```

선택 설정은 `MAIN_COM_GOOGLE_REDIRECT_URI`, `MAIN_COM_GH_APP_WEBHOOK_SECRET`,
`MAIN_COM_MAIL_HOST`, `MAIN_COM_MAIL_PORT`, `MAIN_COM_MAIL_USERNAME`,
`MAIN_COM_MAIL_PASSWORD`, `MAIN_COM_OAUTH2_REDIRECT_URI`다. 사용하는 기능의 실제 값이 필요하다.
`MAIN_COM_SPRING_PROFILES_ACTIVE`는 미등록·빈 값이면 `main`이며 다른 profile은 거부한다.
아래 선택 항목도 `MAIN_COM_` 접두사를 붙인 기존 이름으로 명시적으로 지원한다.
등록하지 않으면 앱의 기존 기본값을 사용하므로 모두 새로 등록할 필요는 없다.

```
APP_BASE_URL BETA_PLATFORM_KEY_ENABLED BETA_DAILY_CALL_QUOTA BETA_TOKEN_BUDGET BETA_ALLOWED_MODELS
GITHUB_APP_PRIVATE_KEY GITHUB_APP_SLUG DISCORD_OPS_WEBHOOK_URL GOOGLE_OAUTH_INCREMENTAL_AUTH
MVC_ASYNC_TIMEOUT_MS DB_POOL_MAX_SIZE DB_POOL_MIN_IDLE DB_POOL_CONNECTION_TIMEOUT_MS
DB_POOL_MAX_LIFETIME_MS DB_POOL_IDLE_TIMEOUT_MS AGENT_TIMEOUT_SECONDS
QUARTZ_THREAD_COUNT QUARTZ_THREAD_NAME_PREFIX WORKFLOW_EXECUTOR_CORE_POOL_SIZE
WORKFLOW_EXECUTOR_MAX_POOL_SIZE WORKFLOW_EXECUTOR_QUEUE_CAPACITY WORKFLOW_EXECUTION_PARALLELISM
GMAIL_TRIGGER_POLL_INTERVAL FORMS_TRIGGER_POLL_INTERVAL
```

`GITHUB_APP_PRIVATE_KEY`도 기존 단일 행 규칙을 유지한다. PEM의 실제 개행을 그대로
주입하지 않으며, 앱이 지원하는 한 줄 base64 PKCS#8 RSA 키를 사용한다.

`application-main.yml`로 별도 자동 migration을 추가하지 않는다. Main controller가
`SPRING_JPA_HIBERNATE_DDL_AUTO=validate`를 강제하며 사용자 입력으로 덮어쓸 수 없다.

Main CORS는 `https://kiacms.co.kr`로 구성하고 각 OAuth 공급자의 등록 callback과 frontend
완료 URL을 대조한다. JWT/AES·Agent 내부 토큰은 Main Blue·Green끼리 같고 DEV와 분리한다.
기존 암호화 데이터를 이관할 경우 AES 키를 임의 교체하면 복호화할 수 없다.
`AGENT_SERVICE_URL`은 컨트롤러가 같은 색상의 Main Agent 주소로 주입한다.
