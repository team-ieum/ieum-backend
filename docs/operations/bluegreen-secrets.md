# 개발 Blue/Green 배포의 Backend 설정

이 문서는 2026-10-28 로컬 수정 기준이다. GitHub 설정·서버 파일·배포는 변경하지 않았다.

## 설정 원본과 접두사

Backend 개발 배포는 **`DEV_` 접두사가 붙은 개별 GitHub Actions Secrets**를 설정 원본으로
사용한다. 접두사를 제거한 이름을 앱 환경변수로 쓰며 기존 `RDS_DB_*`·`GH_APP_*` 9개 별칭은 유지한다.
새로운 일반 앱 설정은 `DEV_실제_앱_변수명`으로 등록하면 workflow의 개별 매핑이나
도구의 허용 목록을 수정하지 않고 수집된다. 앱이 그 변수를 실제로 읽도록 구현돼 있어야 한다.

```text
DEV_RDS_DB_URL       → DB_URL
DEV_GH_APP_CLIENT_ID → GITHUB_APP_CLIENT_ID
DEV_NEW_OPTION       → NEW_OPTION
```

이 Backend 저장소에서 `DEV_`가 붙은 항목은 앱 설정으로 취급한다. `MAIN_`, `DEVELOPMENT_`,
`DEVX_`는 선택하지 않는다. 접두사는 한 번만 제거한다. 이전 `DEV_BACKEND_RDS_DB_URL` 이름을
만들었다면 `DEV_RDS_DB_URL`로 정리해야 하며, 이전 이름을 자동으로 DB_URL에 다시 매핑하지 않는다.

현재 사용자가 전달한 기존 43개 이름은 접두사가 없다. 실제 적용 전에 필요한 값들을 위 규칙의
새 이름으로 등록해야 한다. 기존 이름을 자동 재조회·복사·삭제하거나 GitHub 설정을 바꾸지 않았다.
접두사 없는 기존 Secret·서버 `/home/kwung/deploy/ieum-backend/.env`·기존 runtime·`BACKEND_ENV`
묶음 Secret에서 누락값을 보충하는 fallback은 없다. Main/AWS workflow는 변경하지 않았다.

## 수집과 매번 새로 만드는 후보

1. workflow는 테스트·이미지 빌드·설치 파일 SHA 검증 후 마지막 배포 단계에서만
   `IEUM_BACKEND_SECRETS_JSON: ${{ toJSON(secrets) }}`로 JSON을 받는다.
2. 이 JSON에는 해당 실행이 접근할 수 있는 **전체 Secrets**가 들어간다. runner에 도착하기 전에
   접두사로 필터링되는 것은 아니다. `scripts/bluegreen/backend_env.py`가 접두사가 맞는 항목만 골라
   후보에 기록한다. 접두사 없는 AWS/Main 설정과 자동 `GITHUB_TOKEN`은 앱에 전달하지 않는다.
3. builder는 `mktemp`로 새로 만든 비어 있는 0600 후보에 전체 사용자 환경을 기록한다.
   이전 원본 파일에 append하거나 기존 설정과 합치지 않는다. 검증 실패 시 controller 호출 전에
   중단하고 실행 중인 색상의 설정을 지우지 않는다.
4. shell은 `IEUM_BACKEND_SECRETS_JSON`을 unset한 뒤 후보 경로만 controller에 전달한다.
   `--env-file --replace-env`로 새 후보 색상의 사용자 설정을 전체 교체한다.
5. 정상 종료·실패·INT·TERM에서는 임시 후보를 삭제한다. SIGKILL·호스트 장애에서 shell trap의
   실행은 보장할 수 없으므로 잔존 private 파일도 점검한다.

현재 job에는 `environment:`가 없다. repository Secret 또는 이 저장소 접근을 허용한 organization
Secret을 사용하며, environment 전용 Secret은 자동 제공되지 않는다. `toJSON(secrets)`도 이 실행의
접근 권한을 넘어 다른 저장소·조직·environment의 Secrets를 읽는 기능은 아니다.

수동 서버 원본 `.env` 의존성은 없지만 배포용 임시 파일과 색상별 설정 snapshot은 유지한다.
최종 snapshot은 `/home/kwung/deploy/bluegreen/runtime/{blue|green}/backend.env`이며,
Docker는 이 파일을 읽어 컨테이너 환경변수로 전달한다.

## 기존 필수 설정과 이름 매핑

현재 앱에서 기본값이 없는 사용자 설정 24개는 계속 필수로 검증한다. 누락·빈 문자열·공백만 있는
값은 거부한다. `AGENT_SERVICE_URL`는 controller가 같은 색상의 주소를 넣으므로 사용자 입력이 아니다.
새 Secret 이름은 아래와 같으며 값에는 기존 DEV 값을 등록한다. 표는 실제 값 조회·검증 결과가 아니다.

| 새 GitHub Secret 이름 | 앱 환경변수 이름 |
|---|---|
| `DEV_RDS_DB_URL` | `DB_URL` |
| `DEV_RDS_DB_USERNAME` | `DB_USERNAME` |
| `DEV_RDS_DB_PASSWORD` | `DB_PASSWORD` |
| `DEV_REDIS_HOST` | `REDIS_HOST` |
| `DEV_REDIS_PORT` | `REDIS_PORT` |
| `DEV_MONGO_URI` | `MONGO_URI` |
| `DEV_GOOGLE_CLIENT_ID` | `GOOGLE_CLIENT_ID` |
| `DEV_GOOGLE_CLIENT_SECRET` | `GOOGLE_CLIENT_SECRET` |
| `DEV_CORS_ALLOWED_ORIGINS` | `CORS_ALLOWED_ORIGINS` |
| `DEV_JWT_SECRET` | `JWT_SECRET` |
| `DEV_JWT_EXPIRATION` | `JWT_EXPIRATION` |
| `DEV_JWT_REFRESH_EXPIRATION` | `JWT_REFRESH_EXPIRATION` |
| `DEV_AES_SECRET_KEY` | `AES_SECRET_KEY` |
| `DEV_NOTION_CLIENT_ID` | `NOTION_CLIENT_ID` |
| `DEV_NOTION_CLIENT_SECRET` | `NOTION_CLIENT_SECRET` |
| `DEV_NOTION_AUTH_URL` | `NOTION_AUTH_URL` |
| `DEV_NOTION_TOKEN_URL` | `NOTION_TOKEN_URL` |
| `DEV_NOTION_REDIRECT_URI` | `NOTION_REDIRECT_URI` |
| `DEV_NOTION_FRONTEND_REDIRECT_URI` | `NOTION_FRONTEND_REDIRECT_URI` |
| `DEV_GH_APP_CLIENT_ID` | `GITHUB_APP_CLIENT_ID` |
| `DEV_GH_APP_CLIENT_SECRET` | `GITHUB_APP_CLIENT_SECRET` |
| `DEV_GH_APP_TOKEN_URL` | `GITHUB_APP_TOKEN_URL` |
| `DEV_GH_APP_REDIRECT_URI` | `GITHUB_APP_REDIRECT_URI` |
| `DEV_GH_APP_FRONTEND_REDIRECT_URI` | `GITHUB_APP_FRONTEND_REDIRECT_URI` |

별칭은 위 RDS 3개와 아래 Webhook을 포함한 GH 6개, 총 9개다. 다른 신규 이름에는 자동으로
`RDS_`·`GH_` 변환을 확장하지 않는다. 신규 설정은 실제 앱 환경변수 이름에 접두사를 붙인다.

## 기존 선택 설정과 기본값

| 새 GitHub Secret 이름 | 앱 환경변수 이름 | 미등록·빈 문자열 처리 |
|---|---|---|
| `DEV_SPRING_PROFILES_ACTIVE` | `SPRING_PROFILES_ACTIVE` | 도구에서 `dev` 적용 |
| `DEV_GOOGLE_REDIRECT_URI` | `GOOGLE_REDIRECT_URI` | 키 생략; 앱 기본 `{baseUrl}/api/v1/oauth2/callback/{registrationId}` |
| `DEV_GH_APP_WEBHOOK_SECRET` | `GITHUB_APP_WEBHOOK_SECRET` | 키 생략; 앱 기본 빈 문자열 |
| `DEV_MAIL_HOST` | `MAIL_HOST` | 키 생략; 앱 기본 `smtp.gmail.com` |
| `DEV_MAIL_PORT` | `MAIL_PORT` | 키 생략; 앱 기본 `587` |
| `DEV_MAIL_USERNAME` | `MAIL_USERNAME` | 키 생략; 앱 기본 빈 문자열 |
| `DEV_MAIL_PASSWORD` | `MAIL_PASSWORD` | 키 생략; 앱 기본 빈 문자열 |
| `DEV_OAUTH2_REDIRECT_URI` | `OAUTH2_REDIRECT_URI` | 키 생략; 앱 기본 `http://localhost:3000/oauth2/callback` |

기존 선택 7개는 정확한 빈 문자열이면 생략하고 profile만 `dev`로 채운다. 선택값의 공백을 trim하지
않는다. 반면 위 목록에 없는 **신규 키의 빈 값은 `KEY=`로 보존**한다. 신규 선택 설정을 완전히
제거하려면 Secret을 삭제한다. 새 필수 앱 설정을 fail-fast 검증하려면 필수 검증 목록도 갱신한다.

부팅 필수 여부와 기능 유지 조건은 다르다. TLS 프록시 뒤의 Google 로그인에는 실제 DEV 콜백을
지정하고, OAuth 로그인 완료 콜백·메일 인증·Webhook 검증도 사용하는 기능에 맞는 값을 유지한다.
기존 `AGENT_SERVICE_URL`, `AUTHSOURCE`, `BACKEND_*`, `DOCKER_NETWORK*`, `JAVA_VERSION`,
`RDS_ENDPOINT`를 전부 접두사만 붙여 복사하지 않는다. 컨테이너명·IP·네트워크와 관리 설정은
controller 담당이며, 사용하지 않는 앱 키를 자동 수집하는 것은 필요성 검토를 대신하지 않는다.

## 입력 검증과 예약된 설정

- JSON은 UTF-8 object여야 한다. 중복 JSON 이름, 비표준 NaN/Infinity, 잘못된 JSON·과도한 중첩은
  고정 오류로 거부한다. 선택한 값은 문자열이어야 한다.
- Secret 이름은 대소문자를 정규화하되 선택한 원문 이름과 접두사 뒤 이름 모두 ASCII 환경변수
  형식을 만족해야 한다. 이름이 비거나 숫자로 시작하거나 Unicode 변환으로 ASCII가 된 이름은 거부한다.
- 대소문자·별칭 변환·Spring relaxed binding(underscore를 무시한 동일 이름)의 충돌은 거부한다.
  예를 들어 `DEV_RDS_DB_URL`과 `DEV_DB_URL`을 동시에 등록하지 않는다.
  기존 선택값이 빈 문자열이어도 생략 전에 충돌을 확인한다. 결과는 앱 키 이름순으로 기록한다.
- controller 소유인 `AGENT_SERVICE_URL`, `IEUM_AGENT_URL`, DDL·시간대·종료·관리 endpoint 설정,
  `IEUM_DEPLOYMENT_*`·`IEUM_SCHEDULER_*`와 queue의 consumer/cutoff/expected-zone 키는 거부한다.
  같은 속성의 underscore 변형도 거부해 같은 색상 Agent 주소나 기동 drain을 우회하지 못하게 한다.
- 우선순위가 높은 `SPRING_APPLICATION_JSON`, JVM 실행 옵션 `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`,
  `_JAVA_OPTIONS`, `JAVA_OPTS`, 자동 토큰 `GITHUB_TOKEN`, 묶음 `BACKEND_ENV`, JSON 전달 변수도
  사용자 앱 설정으로 받지 않는다. `SPRING_CONFIG_*` 외부 설정 유입과 `SPRING_JPA_*`의 vendor DDL,
  `SPRING_SQL_INIT_*`·`SPRING_FLYWAY_*`·`SPRING_LIQUIBASE_*` 스키마 변경 설정도 운영 전용으로 거부한다.
  Hikari connection-init SQL과 connection-test query도 기동·health 전 SQL 실행 경로이므로 거부한다.
  일반 pool 튜닝 키는 수집한다. 전체 예약 목록은 `RESERVED_KEYS`·`RESERVED_PREFIXES`를 따른다.
- 각 값은 UTF-8 단일 행이며 LF·CR·NUL·VT/FF·FS/GS/RS·NEL·U+2028/U+2029를 거부한다.
  마지막 줄바꿈도 제거하지 않는다. `$()`·backtick·따옴표·공백·`=`는 literal이며 실행·확장하지 않는다.
- 원본 전체 JSON은 **96KiB**, 선택 후 후보 파일은 **48KiB** 이하로 제한한다. 이는 도구의 제한이며
  GitHub 개별 Secret 제한과 다르다. 접두사 없는 Secrets도 JSON 크기에 포함된다.
- 큰 JSON은 OS/runner의 환경변수 전달·프로세스 생성 제한으로 Python 실행 전에 실패할 수 있다.
  이 경우 도구의 고정 오류까지 보장하지 않으며 서버 원본이나 기존 설정으로 대체하지 않는다.
- 후보는 배포 계정 소유의 비어 있는 regular file, 권한 0600, hard link 수 1이어야 한다.
  symlink·공개 파일·다른 소유자·기존 내용이 있는 파일은 덮어쓰지 않는다.

형식·필수 검증은 실제 DB·OAuth·SMTP 인증이나 업무상 설정의 유효성을 증명하지 않는다.
현재 runtime과 선택한 후보를 비공개 환경에서 키 단위로 비교하고 기능·health 검증을 수행한다.

## 전체 교체와 색상별 설정 보존

새 후보는 매번 선택한 접두사 Secrets만으로 처음부터 생성한다. `--replace-env`를 유지하므로
Secret에서 삭제한 신규 키는 다음 Backend 후보에서 제거되고, 필수값 삭제는 배포를 차단한다.
기존 선택값 삭제·빈값은 키 생략/앱 기본값, profile 삭제·빈값은 `dev`로 처리한다.
이미지 기본 환경과 controller의 같은 색상 주소·기동 drain·분산 제어·DDL validate·불변 pin은 별도다.

- 새 Backend 배포에서만 현재 접두사 Secrets를 읽는다.
- Frontend·Agent만 배포하면 Backend는 기존 배포 snapshot을 재사용한다.
- retained 색상으로 rollback할 때는 당시 컨테이너·이미지·설정을 사용한다.
- 새 Secret 등록·수정만으로 실행 중인 active·retained 설정이 자동 갱신되지는 않는다.
- repository·organization Secrets는 run queue 시점의 값이므로 변경 후에는 새 run을 시작한다.

수동 bootstrap·복구에도 승인된 실행에서 동일 JSON 입력으로 같은 builder를 사용한다.
GitHub API에서 저장한 Secret 평문을 다시 내려받거나 장기 서버 원본 `.env`를 보충하는 경로는 없다.
필요한 추가 키가 예약 설정이면 우회 이름을 쓰지 말고 배포 정책·도구를 별도 검토한다.

## 보안상 의미와 한계

개별 Secrets로 등록하면 값별 관리·교체·마스킹의 이점은 유지된다. 다만 `toJSON(secrets)`는 전체
접근 가능 Secrets를 해당 runner 단계에 일시 제공한다. 접두사 필터는 **앱 전달 범위**를 제한하며
runner의 원본 접근 범위를 줄이는 기능은 아니다. 원문 JSON이나 선택한 값을 출력·업로드하지 않는다.
Secret을 shell 코드·argv·`GITHUB_ENV`·빌드 입력에 삽입하지 않고 후보 생성 뒤 JSON을 unset한다.

배포 중 runner 프로세스·private 후보·snapshot과 Docker 설정에는 평문 값이 존재한다.
Docker 관리자에게 환경변수를 숨기는 저장소나 메모리 전용 구성은 아니다. 자동 수집은 운영자에게
Secrets 편집 권한을 제한하는 작업이나 실제 설정의 의미 검토를 대신하지 않는다.

## 로컬 검증

```bash
python3 -m unittest discover -s scripts/bluegreen -p 'test_*.py'
```

테스트는 합성 JSON·가짜 controller·임시 파일을 사용하고 실제 Secrets·Docker·서버에 접속하지 않는다.
workflow `Test` 단계에서도 Gradle 전에 같은 Python 검증을 실행한다.

[GitHub Secrets 컨텍스트](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#secrets-context),
[toJSON 함수](https://docs.github.com/en/actions/reference/workflows-and-actions/expressions#tojson),
[GitHub 보안 권고](https://docs.github.com/en/actions/reference/security/secure-use).
