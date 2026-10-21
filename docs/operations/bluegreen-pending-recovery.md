# Blue/Green PENDING 복구 관측 정책

자동 복구는 active 소유자만 수행한다. 운영자가 확정한 `pending-recovery-not-before` 이후이며,
기본 1시간 이내에 준비된 PENDING만 대상으로 한다. 기본 grace는 1분, 재발행 예산은 3회다.
범위 밖이나 예산 초과 행을 자동 FAILED로 바꾸거나 삭제하거나 과거 트리거를 재실행하지 않는다.

## 대기 작업이 복구 창을 벗어났을 때

active 소유자는 기본 1분마다 다음 두 범위를 각각 최대 101행 조회한다. 쿼리에는 3초 timeout
hint를 적용한다. 결과는 oldest-first이며 100행을 넘으면 경고와 gauge에 초과 여부를 표시한다.

- `before_cutoff`: 운영자가 정한 도입 cutoff 이전의 PENDING.
- `age`: cutoff 이후지만 기본 1시간 복구 창보다 오래된 PENDING.

`운영자 검토 필요` WARN은 범위, 최대 100행인 표본 수, 초과 여부, 가장 오래된 실행 ID와
준비 시각을 포함한다. `before_cutoff`는 최초 비어 있지 않은 관측에서 프로세스당 1회만
WARN 요약을 남긴다. 관측과 gauge 갱신은 계속되며, 소유권을 잃었다 되찾아도 이 경고를
다시 내지 않는다. `age`는 기존 관측 주기마다 WARN을 반복하므로 61분 된 PENDING은
계속 드러난다. 정상화하면 INFO를 남기고 해당
gauge를 0으로 되돌린다. candidate/retained는 이 조회나 경고를 수행하지 않으며, 소유권을
잃으면 gauge를 0으로 되돌린다. 관측 실패는 별도 WARN/counter로 알리고 이전의 성공한
관측값과 시각을 보존한다. 관측 실패가 창 안의 복구를 막지는 않는다.

`ieum.workflow.queue.pending-recovery-observation-interval`로 관측 간격을 조정할 수 있다.
기본 `PT1M`, 최솟값 `PT20S`다. 관측값은 전체 정확한 행 수가 아닌 bounded sample이다.

## Micrometer 지표

다음 지표는 기존의 보안된 수집 경로를 사용할 때 활용할 수 있다. 이 변경은 Actuator
노출 목록이나 공개 Nginx 경로를 늘리지 않는다. 기본 운영 설정에서 metrics HTTP endpoint가
노출되지 않더라도 위 WARN 로그는 남는다.

| 이름 | 의미 |
| --- | --- |
| `ieum.workflow.pending.recovery.excluded.sampled{reason}` | 제외 PENDING 표본 수, 최대 100 |
| `ieum.workflow.pending.recovery.excluded.limit_reached{reason}` | 추가 행 존재 시 1 |
| `ieum.workflow.pending.recovery.excluded.oldest_age_seconds{reason}` | 표본 내 가장 오래된 행의 나이 |
| `ieum.workflow.pending.recovery.observed_at_epoch_seconds` | 마지막 성공 관측 시각, 소유권 없으면 0 |
| `ieum.workflow.pending.recovery.batch{result}` | 마지막 복구 batch의 `attempts_exhausted` / `deferred` 수 |
| `ieum.workflow.pending.recovery.results{result}` | 복구 결과 관측 횟수, 실행별 고유 개수가 아님 |
| `ieum.workflow.pending.recovery.observation.failures` | 창 밖 관측 실패 횟수 |
| `ieum.workflow.pending.recovery.failures` | 창 안 복구 처리 실패 횟수 |
| `ieum.workflow.pending.recovery.timezone.validation_failures` | 실행 중 시간대 검증에 실패해 차단된 주기 횟수 |
| `ieum.workflow.pending.recovery.timezone.valid` | 마지막 시간대 검증 정상일 때 1 |
| `ieum.workflow.pending.recovery.timezone.blocked` | 시간대 검증 때문에 복구가 차단됐을 때 1 |

Gauge는 정상화·소유권 상실에 따라 초기화하고 counter는 누적한다.
시간대 valid/blocked gauge는 소유권과 별도로 검증 상태를 나타낸다. 시간대 차단은 기존
`recovery.failures`와 전용 `timezone.validation_failures` counter 양쪽에 포함된다.

## 실행 중 시간대가 달라진 경우

기동 시 누락·불일치는 계속 기동 실패로 처리한다. 실행 중 검증 실패는 주기 작업 밖으로
반복 예외를 던지지 않고 DB 조회·재발행 전에 중단한다. 동일 불일치의 WARN은 최초와
최대 5분마다 남기며, expected/JVM/Clock 값이 바뀌면 즉시 다시 남긴다. 매 차단 주기는
counter에 포함된다. 운영자가 원래의 확인된 시간대를 복원하면 다음 주기(기본 20초)에
정상화 INFO를 1회 남기고 자동 복구를 재개한다. 별도의 clear 명령은 없고 counter는 유지한다.

기존 내부 `/actuator/deployment` 응답의 `pendingRecoveryTimezone` 객체에서 확인할 수 있다.
새 endpoint나 공개 metrics 경로는 추가하지 않는다. 값은 마지막 검사 기준이며
`checkedAtEpochSeconds`로 시점을 확인한다.

- `initialized`, `valid`, `blocked`: 검증 여부와 현재 복구 차단 상태.
- `reason`: 정상은 빈 문자열, 불일치는 `zone_mismatch`, 설정 오류는 `invalid_expected_zone`.
  아직 검사하지 않았으면 `not_validated`다.
- `expectedZone`, `jvmZone`, `clockZone`: 확인된 기대값과 실제 JVM·Clock 값.
- `checkedAtEpochSeconds`, `blockedSinceEpochSeconds`, `lastBlockedAtEpochSeconds`: 마지막 검사,
  현재 차단 시작, 마지막 차단 시각. 정상화하면 현재 차단 시작 값은 0으로 돌아간다.
- `blockedChecks`: 프로세스의 누적 차단 주기 수. 정상화해도 유지한다.

## 운영자 판단과 스케줄 보호

WARN이 발생하면 해당 실행의 DB 상태, stream/PEL, 실행 로그 및 외부 서비스에서 발생한
부수 효과를 먼저 확인한다. cutoff/나이 창을 넓히거나 Redis budget을 초기화하는 행위는
과거 작업을 다시 실행시킬 수 있으므로 자동 조치로 사용하지 않는다. 재실행이나 실패 확정은
개별 실행을 검토한 뒤 별도의 명시적 운영 절차로 결정한다.

미종료 SCHEDULE 실행을 최대 2시간 확인하는 기존 중복 방지 조건은 유지한다. 자동 복구
창을 벗어났다는 이유로 해당 PENDING을 무시하면 동일 스케줄의 외부 부수 효과가 중복될 수
있다. 따라서 이 변경은 다음 스케줄을 자동으로 해제하지 않는다.

## Legacy 재bootstrap과 시간대 경계

- Legacy worker는 새 marker 정리 Lua를 사용하지 않으므로 재bootstrap 중 처리한 실행의
  `ieum:exec:published:*` 키가 남을 수 있다. 정상적인 protocol 2 ACK는 원자적으로 정리한다.
  이 변경은 Legacy marker를 추측으로 삭제하지 않는다. 정리하려면 DB terminal 상태,
  관련 stream/PEL 부재, marker가 여전히 같은 항목을 가리키는지 먼저 확인해야 한다.
- `startedAt`은 시간대 정보가 없는 LocalDateTime이다. 시작 정책 INFO에 cutoff와 유효 JVM
  시간대·기대 시간대를 기록하므로 Legacy와 새 컨테이너의 TZ/JVM 설정 및 과거 DB 기록
  기준을 비교한다. 분산 모드에는 운영자가 확인한
  `ieum.workflow.queue.pending-recovery-expected-zone`
  (`IEUM_WORKFLOW_QUEUE_PENDING_RECOVERY_EXPECTED_ZONE`)이 반드시 필요하다. 누락, 잘못된
  ZoneId, 기대 시간대와 실제 JVM/Clock 시간대의 불일치는 기동을 거부한다. 복구 직전에도
  확인해 시간대가 달라진 상태에서는 DB 조회·재발행을 시작하지 않는다.
- ZoneId는 대소문자를 구분한다. `UTC`/`Etc/UTC` 등 역사·미래 규칙이 완전히 같은 alias는
  허용하지만 현재 offset만 같은 `Asia/Seoul`/`+09:00`은 같은 기록 기준으로 취급하지 않는다.
  `-Duser.timezone` 등 JVM 옵션이 `TZ`와 다른 실제 시간대를 만들면 불일치 검사가 걸린다.
  Java 옵션을 지우거나 기존 DB timestamp를 변환하지 않는다.
- controller는 최초 운영자 확인값을 `--backend-time-zone` 또는 기대 시간대 env로 받아
  `control/backend-time-zone`에 고정하고 후속 후보의 `TZ`와 기대 시간대를 계속 주입한다.
  `--replace-env` 파일에 `TZ`가 빠져도 확인한 기준이 사라지지 않아야 한다. `TZ` 존재 자체는
  과거 DB 기록 기준을 검토했다는 의미가 아니므로 별도의 명시적 확인값이 필요하다.
