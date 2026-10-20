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
준비 시각을 포함한다. 61분 된 PENDING도 계속 관측한다. 정상화하면 INFO를 남기고 해당
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

Gauge는 정상화·소유권 상실에 따라 초기화하고 counter는 누적한다.

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
  시간대를 기록하므로 Legacy와 새 컨테이너의 TZ/JVM 설정 및 과거 DB 기록 기준을 비교한다.
  이 변경은 기존 timestamp를 변환하거나 TZ를 덮어쓰지 않는다. 두 버전의 시간대가 다르면
  재적용 전에 기준을 확정해야 한다.
