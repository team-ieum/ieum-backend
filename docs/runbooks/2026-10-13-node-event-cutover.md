# 노드 event 단일화 dev 전환 런북 (IEUM-AI-78 · IEUM-BE-92)

- spec: `docs/superpowers/specs/2026-10-13-node-event-catalog-design.md` §6
- 대상: dev 서버 `kwung@kwungserver`(Orca 환경 `IEUM_DEV`). 운영 DB는 없다(2026-10-13 사용자 확인).
- 카탈로그: `team-ieum/ieum-node-catalog` main `105975a602569a159f66fe8fb9091865006a1153`(PR #1 `656a92b`·#2 `105975a` 머지 완료). agent `feat/IEUM-AI-78`과 BE `feat/IEUM-BE-92`의 `catalog/` 서브모듈이 이미 이 커밋이다.
- 이 런북의 쓰기 명령(§5 멈춤, §8 삭제, §1-2 서브모듈 갱신 커밋·push(다를 때만), §1-3 dev 머지(= dev 자동 배포), §11 롤백)은 **사용자가 확인한 뒤에만** 실행한다. 읽기 명령(§4, §6, §7, §9)은 바로 돌려도 된다. §10 E6(실제 Slack 발송)·E8(실제 메일 수신)·E9(실제 GitHub 이슈 생성)은 외부에 부작용이 남으므로 **사용자 확인 후** 한다.

## 0. 무엇을 지우고 무엇을 남기나 (엔티티 `@Table`·`docs/schema` 전수 확인, BE `f1d92fb`, `e7332de`에서 재확인 — 15개 그대로)

**삭제 대상 PG 테이블 9개** — 옛 노드 모양을 담고 있거나 그런 행에 종속된다.

| 테이블 | 엔티티 | FK (Hibernate 생성) |
|---|---|---|
| `node_runs` | `WorkflowExecutionLog` | `execution_id` → `workflow_runs` |
| `workflow_runs` | `WorkflowExecution` | `workflow_id` → `workflows`, `workflow_version_id` → `workflow_versions` |
| `node_test_samples` | `NodeTestSample` | `workflow_id` → `workflows` |
| `app_trigger_subscriptions` | `AppTriggerSubscription` | 없음(`workflow_id`는 uuid 칼럼) |
| `workflow_webhook_tokens` | `WorkflowWebhookToken` | 없음(`workflow_id`는 uuid 칼럼) |
| `chat_messages` | `ChatMessage` | `session_id` → `chat_sessions` |
| `chat_sessions` | `ChatSession` | 없음(`workflow_id`는 uuid 칼럼) |
| `workflow_versions` | `WorkflowVersion` | `workflow_id` → `workflows` |
| `workflows` | `Workflow` | 없음 |

**남기는 PG 테이블 6개**: `users`, `connected_accounts`, `user_ai_credentials`, `webhook_credentials`, `mcp_server_catalogs`, `prompt_templates`. 사용자·연동·크레덴셜·MCP·프롬프트는 노드 모양과 무관하다.

**PG에 없는 것**
- Quartz 테이블(`qrtz_*`): 없다. `SchedulerConfig`가 `RAMJobStore`를 쓰므로 Job은 BE 프로세스 메모리에만 있다. BE를 멈추면 사라지고, 기동할 때 `ScheduleJobRestorer`가 PG `workflows`에서 다시 만든다. 그래서 "Quartz 삭제" = PG 행 삭제(§8, BE 정지 중) + 새 BE 기동(§1-3 배포)이다.
- 실행 큐 테이블: 없다. 큐는 Redis Stream이다(아래).

**Mongo**
- 삭제: `workflow_definitions`(BE `WorkflowDefinitionDocument`), `agent_sessions`(agent `db/mongodb.py`). 컬렉션과 인덱스(agent_sessions TTL)는 그대로 두고 문서만 지운다.
- 남김: agent 로그 `execution_logs`·`chat_logs`·`generate_workflow_logs`·`idempotency_records`(TTL). 노드 모양이 없고 지표 원천이다. 카탈로그 이전용 `node_templates`도 남긴다(spec §8, finetune이 읽는다).

**Redis**
- 삭제: `ieum:exec:jobs`(실행 잡 스트림, 그룹 `runners`). BE 기동 시 `ExecutionJobQueueBootstrap`이 `MKSTREAM`으로 다시 만든다. `webhook:listen:*`(노드 테스트 listen, TTL 5분)도 지운다.
- 남김: `beta:calls:*`·`beta:tokens:*`(쿼터), `idem:*`(TTL로 만료), `alert:cooldown:*`.

## 1. 진행 순서

순서: §2 접근 → §3 셸 → §4 사전 점검 → §5 옛 BE·agent 정지 → §6 백업 → §7 지표 → **§8 삭제** → 아래 1~3(확인·머지·배포) → §9 health 확인 → §10 e2e.

- [ ] 0. **머지·배포 전에 옛 코드 데이터를 보존하고 지운다**: §2 접근 → §3 셸 준비 → §4 사전 점검 → **§5 옛 BE·agent 정지 → §6 백업 → §7 지표 보고서 → §8 삭제**. 옛 코드가 멈춘 상태에서 백업·집계하므로 그 사이 행이 바뀌지 않고, 지표는 옛 코드가 만든 데이터만 센다(§7 쿼리는 옛 필드 기준 그대로). 삭제도 옛 컨테이너가 멈춘 상태에서 하므로, 새 BE가 뜨자마자 옛 SCHEDULE·Gmail 구독을 event 없는 노드로 도는 구간이 없다. 정지는 아래 3의 배포가 새 컨테이너를 띄울 때까지 유지한다.
- [ ] 1. **카탈로그 커밋 확인(읽기).** 카탈로그 PR #1·#2는 이미 main에 머지됐다. BE 레포 루트에서:
  ```bash
  CAT_SHA=$(git -C catalog rev-parse HEAD); echo "$CAT_SHA"        # 105975a602569a159f66fe8fb9091865006a1153
  git -C catalog fetch origin && git -C catalog merge-base --is-ancestor HEAD origin/main && echo on-main
  ```
- [ ] 2. **두 레포 서브모듈이 같은지 확인한다.** BE 레포 루트(워크스페이스에서 `ieum-agent`와 형제 폴더)에서 실행한다. **`같음`이 나오지 않으면 머지하지 않는다.**
  ```bash
  git fetch origin && git -C ../ieum-agent fetch origin
  # 세 값(agent 서브모듈·BE 서브모듈·§1-1의 $CAT_SHA)이 모두 같아야 `같음`. $CAT_SHA는 §1-1과 같은 셸에서 적은 값
  test -n "$CAT_SHA" &&
  test "$(git -C ../ieum-agent rev-parse origin/feat/IEUM-AI-78:catalog)" = "$CAT_SHA" &&
  test "$(git rev-parse origin/feat/IEUM-BE-92:catalog)" = "$CAT_SHA" && echo 같음
  # 두 PR의 CI·리뷰 상태(읽기). checks가 전부 pass + state OPEN + reviewDecision이 CHANGES_REQUESTED가 아닐 때만 머지한다
  # 리뷰는 pr-loop로 진행하고 GitHub approval은 쓰지 않는다(reviewDecision은 보통 "")
  gh pr checks feat/IEUM-AI-78 --repo team-ieum/ieum-agent
  gh pr checks feat/IEUM-BE-92 --repo team-ieum/ieum-backend
  gh pr view feat/IEUM-AI-78 --repo team-ieum/ieum-agent   --json state,reviewDecision
  gh pr view feat/IEUM-BE-92 --repo team-ieum/ieum-backend --json state,reviewDecision
  ```
  **다를 때만**(쓰기 — 사용자 확인 후) 어긋난 레포의 PR 브랜치에서 서브모듈을 `$CAT_SHA`로 올려 커밋·push하고 위 확인을 다시 돈다.
  ```bash
  git -C catalog fetch origin && git -C catalog checkout "$CAT_SHA"
  git add catalog && git commit -m "chore: 카탈로그 서브모듈을 머지 커밋으로 갱신" -m "IEUM-BE-92"   # agent는 본문 IEUM-AI-78
  git submodule status   # $CAT_SHA
  ```
- [ ] 3. **같은 날, 연달아** agent PR → BE PR 순으로 dev에 머지한다(쓰기 — 사용자 확인 후. 머지 = dev 자동 배포). 각 push가 `agent-dev-server.yml`·`backend-dev-server.yml`을 돌린다. 둘 다 `submodules: true`이고, BE는 Test 단계까지 거친다. agent를 먼저 올리는 이유: ACTION 칸 경로와 event 필수가 한쪽만 바뀐 시간을 짧게 하고, BE ACTION 실행이 새 agent를 부르게 하기 위해서다.
- [ ] 4. 두 배포의 Health check가 초록이면 §9(health·로그 확인) → §10 e2e. 데이터는 0에서 이미 비웠으므로 새 BE는 복구할 Quartz Job이 없고 잡 그룹을 새로 만든다 — 재시작은 필요 없다.
- [ ] 5. e2e를 통과한 뒤 §12 워크스페이스 CLAUDE.md를 갱신한다.
- [ ] 6. IEUM-BE-93(트리거 핸들러·기동 검사)을 머지·배포한다.
- [ ] 7. FE 명세 이슈를 등록한다. FE가 반영하기 전까지 dev 빌더로는 저장할 수 없다(사용자가 감수하기로 함).
- 후속 메모(BE-92 범위 밖, 후속 이슈 후보): agent는 채팅 `currentNodes`를 event 외 사유(label·llmProvider·mappings·cron·HTTP method/url 없음)로도 422할 수 있고, BE는 이를 `PROVIDER_ERROR`(502)로 낸다(4xx 본문은 로그에 남기지 않고 상태 코드만 — `AgentClient.toAgentError`).

## 2. dev 접근

메모리 `reference-dev-db-access` 기준이다. 2026-10-11부터 kwungserver는 Orca 환경 `IEUM_DEV`라서 **모든 `orca terminal` 명령에 `--environment`가 필요하다**.

```bash
orca environment list --json                                  # IEUM_DEV id (2026-10-11 기준 55d1823c-c314-42f0-b3bb-53b659bfd323)
ENV=<IEUM_DEV id>
orca terminal list --environment "$ENV" --json                # title kwung@kwungserver 핸들 찾기
orca terminal send --environment "$ENV" --terminal <h> --text "<명령>" --enter --json
orca terminal read --environment "$ENV" --terminal <h> --json # result.terminal.tail
```
- 탭이 `connected:false`면 `orca terminal show`로 worktreeId를 찾고 `orca terminal create --environment "$ENV" --worktree "id:<worktreeId>" --title cutover --json`을 실행한다.
- 사용자 화면에 그대로 보이는 터미널이다. 긴 블록은 사용자가 붙여 넣는 편이 낫다.
- 컨테이너: `ieum-backend`(10.10.10.11:8080), `ieum-agent`(10.10.10.14:8000), `ieum-postgres`, `ieum-mongo`, `ieum-redis`.

## 3. 셸 준비 (서버, 한 번)

**bash에서 실행한다**(`for t in $1`·`$TABLES_DEL`이 bash 단어 분할에 기대다). `echo $0`로 확인하고 zsh면 `bash`로 들어간다.

먼저 서버인지 확인한다. 로컬 docker-compose도 컨테이너 이름(`ieum-postgres`·`ieum-redis`·`ieum-mongo`)과 env 이름이 같아서 아래 함수가 노트북에서도 성공한다 — `on-server`가 나와야 진행한다. 서버 hostname은 §2로 접속한 뒤 `hostname -s`로 확인하고, `kwungserver`와 다르면 이 런북의 `kwungserver` 조건(§3·§5·§8)을 그 값으로 고친다.

```bash
[ "$(hostname -s)" = kwungserver ] && echo on-server
D=$(date +%F)                       # 예: 2026-10-14. 노트북에서도 같은 값으로 쓴다
B=~/ieum-dev-backup-$D
mkdir -p "$B" && chmod 700 "$B"
TABLES_DEL="node_runs workflow_runs node_test_samples app_trigger_subscriptions workflow_webhook_tokens chat_messages chat_sessions workflow_versions workflows"
TABLES_KEEP="users connected_accounts user_ai_credentials webhook_credentials mcp_server_catalogs prompt_templates"
psqlq() { docker exec -i ieum-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X -v ON_ERROR_STOP=1 "$@"' psql "$@"; }
mongojs() {  # 사용: mongojs <DB이름> <js파일>
  docker cp "$2" ieum-mongo:/tmp/runbook.js &&
  docker exec -e MDB="$1" ieum-mongo sh -c 'mongosh --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin "$MDB" --file /tmp/runbook.js'
}
counts() { for t in $1; do echo "SELECT '$t', count(*) FROM $t;"; done | psqlq -At; }
```

## 4. 사전 점검 (읽기 전용)

**먼저 노트북(BE 레포 루트)에서 §1-1·§1-2의 읽기 블록(카탈로그 커밋, 원격 PR 브랜치 서브모듈 `같음`, 두 PR checks pass·state OPEN·reviewDecision이 CHANGES_REQUESTED 아님)을 돌린다.** 하나라도 실패하면 §5 이후(정지·삭제)로 가지 않는다 — 데이터를 지운 뒤에 머지가 막히면 dev가 빈 채로 멈춘다.

```bash
# (서버) — §3 셸에서
docker exec ieum-postgres pg_dump --version
docker exec ieum-mongo mongoexport --version
docker exec ieum-mongo sh -c 'test -n "$MONGO_INITDB_ROOT_USERNAME" && echo mongo-root-env-ok'
docker exec ieum-redis redis-cli PING                          # PONG (BE application.yml에 redis 비밀번호 없음)

# 4-1 PG 테이블 전체. §0의 15개 밖의 테이블이 있으면 멈추고 사용자에게 묻는다
echo "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY 1;" | psqlq -At
echo "SELECT count(*) AS qrtz_tables FROM pg_tables WHERE tablename LIKE 'qrtz%';" | psqlq -At     # 0

# 4-2 삭제 대상을 가리키는 FK 전부. child가 삭제 대상 9개 밖이면 멈추고 사용자에게 묻는다(그대로 가면 §8 TRUNCATE가 실패한다)
cat > "$B/fk.sql" <<'FK_SQL'
SELECT conrelid::regclass AS child, confrelid::regclass AS parent, conname
FROM pg_constraint
WHERE contype = 'f'
  AND confrelid::regclass::text IN ('workflows','workflow_versions','workflow_runs','node_runs','node_test_samples',
                                    'app_trigger_subscriptions','workflow_webhook_tokens','chat_sessions','chat_messages')
ORDER BY 2, 1;
FK_SQL
psqlq < "$B/fk.sql"

# 4-3 진행 중 실행 (참고용)
echo "SELECT status, count(*) FROM workflow_runs WHERE status IN ('PENDING','RUNNING','WAITING_APPROVAL') GROUP BY 1;" | psqlq

# 4-4 Mongo: 두 컬렉션이 어느 DB에 있는지 → MDB_WF, MDB_AG
cat > "$B/q_dbs.js" <<'DBS_JS'
db.adminCommand({listDatabases: 1}).databases.forEach(d => {
  const x = db.getSiblingDB(d.name);
  const hit = x.getCollectionNames().filter(c => ["workflow_definitions", "agent_sessions", "execution_logs"].includes(c));
  if (hit.length) print(d.name + "\t" + hit.map(c => c + "=" + x.getCollection(c).countDocuments({})).join(" "));
});
DBS_JS
mongojs admin "$B/q_dbs.js"
MDB_WF=<workflow_definitions가 있는 DB>   # 보통 ieum
MDB_AG=<agent_sessions가 있는 DB>         # agent MONGODB_DB_NAME 기본값 ieum

# 4-5 Redis
docker exec ieum-redis redis-cli XLEN ieum:exec:jobs
docker exec ieum-redis redis-cli --scan --pattern 'webhook:listen:*'
```

## 5. 옛 서비스 멈춤 (쓰기 — 확인 후, 머지·배포 전)

시작 전에 두 레포(ieum-backend·ieum-agent) dev 머지를 동결한다 — 팀에 공지한 뒤 시작하고 §10이 끝나면 해제한다(dev push가 옛 코드 BE·agent를 자동 재기동한다).

```bash
[ "$(hostname -s)" = kwungserver ] && docker stop ieum-backend ieum-agent || echo "NOT-SERVER 또는 stop 실패 — 멈추고 확인"
docker ps --format '{{.Names}}' | grep -E '^ieum-(backend|agent)$' || echo stopped
docker ps -a --filter name=ieum-backend --filter name=ieum-agent --format '{{.Names}}\t{{.Status}}'   # 둘 다 Exited
```
BE를 멈추면 Quartz(RAMJobStore)·Gmail 폴링(`GmailTriggerSweeper`, 60초 간격)·Redis 잡 큐 워커가 모두 멈춘다 — 셋 다 BE 프로세스 안에 있다. 실제로 안 도는지 행 변화로 확인한다:
```bash
snap() {
  echo "SELECT (SELECT count(*) FROM workflow_runs), (SELECT max(updated_at) FROM workflow_runs), (SELECT max(updated_at) FROM app_trigger_subscriptions);" | psqlq -At
  docker exec ieum-redis redis-cli XLEN ieum:exec:jobs
  docker exec ieum-redis redis-cli XINFO GROUPS ieum:exec:jobs
}
snap > "$B/stop_snap1.txt"; sleep 150; snap > "$B/stop_snap2.txt"
diff "$B/stop_snap1.txt" "$B/stop_snap2.txt" && echo quiet
```
`quiet`이면 2분 반 동안 실행 행(Quartz 발화·잡 워커)·구독 커서(Gmail 폴링)·잡 스트림이 그대로다. 다르면 멈추고 무엇이 아직 도는지(다른 컨테이너·호스트) 사용자에게 묻는다. 이후 §6·§7 동안 PG·Mongo·Redis 행이 바뀌지 않는다. 정지는 §8 삭제를 거쳐 §1-3 배포가 새 컨테이너를 띄울 때까지 유지한다(다시 띄우지 않는다).

## 6. 원본 백업

```bash
counts "$TABLES_DEL"  > "$B/counts_before_del.txt"
counts "$TABLES_KEEP" > "$B/counts_before_keep.txt"

for t in $TABLES_DEL; do
  docker exec ieum-postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --data-only --table="public.$0"' "$t" > "$B/pg_$t.sql" \
    || echo "실패: $t"
done

docker exec -e MDB="$MDB_WF" ieum-mongo sh -c 'mongoexport --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin -d "$MDB" -c workflow_definitions --jsonFormat=canonical' > "$B/mongo_workflow_definitions.jsonl"
docker exec -e MDB="$MDB_AG" ieum-mongo sh -c 'mongoexport --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin -d "$MDB" -c agent_sessions --jsonFormat=canonical' > "$B/mongo_agent_sessions.jsonl"
wc -l "$B"/mongo_*.jsonl          # §4-4의 countDocuments와 같아야 한다

ls -la "$B"; cat "$B/counts_before_del.txt"
```
- `pg_<테이블>.sql`마다 `COPY public.<테이블> ... FROM stdin;` 블록이 있어야 한다. 행이 0이어도 블록은 있다. 확인: `grep -L '^COPY public\.' "$B"/pg_*.sql` → 출력이 없어야 한다(나오는 파일은 블록이 없다).

## 7. 지표 요약 보고서 (옛 코드 정지 직후·배포 전 기준)

### 7-1 PG 집계

```bash
cat > "$B/metrics.sql" <<'METRICS_SQL'
\pset footer off
\echo '## 1. 기간 (created_at·started_at은 앱 LocalDateTime=KST 그대로)'
SELECT 'workflows' AS src, min(created_at) AS first_at, max(created_at) AS last_at FROM workflows
UNION ALL SELECT 'workflow_runs', min(started_at), max(started_at) FROM workflow_runs
UNION ALL SELECT 'chat_sessions', min(created_at), max(created_at) FROM chat_sessions;

\echo '## 2. 워크플로우·버전'
SELECT (SELECT count(*) FROM workflows) AS workflows,
       (SELECT count(*) FROM workflows WHERE is_active) AS active_workflows,
       (SELECT count(*) FROM workflow_versions) AS versions,
       (SELECT round(avg(c), 2) FROM (SELECT count(*) AS c FROM workflow_versions GROUP BY workflow_id) v) AS avg_versions_per_workflow,
       (SELECT max(c) FROM (SELECT count(*) AS c FROM workflow_versions GROUP BY workflow_id) v) AS max_versions;

\echo '## 3. 트리거 종류별 워크플로우 수 (workflows.trigger_type)'
SELECT trigger_type, count(*) AS workflows FROM workflows GROUP BY 1 ORDER BY 2 DESC;

\echo '## 4-1. 실행 수 (상태별)'
SELECT status, count(*) AS runs FROM workflow_runs GROUP BY 1 ORDER BY 2 DESC;

\echo '## 4-2. 실행 수·성공률·평균 소요 (트리거 종류별, 성공률 = SUCCESS/(SUCCESS+FAILED))'
SELECT COALESCE(trigger_type, '(전체)') AS trigger_type,
       count(*) AS runs,
       count(*) FILTER (WHERE status = 'SUCCESS') AS success,
       count(*) FILTER (WHERE status = 'FAILED') AS failed,
       round(100.0 * count(*) FILTER (WHERE status = 'SUCCESS')
             / NULLIF(count(*) FILTER (WHERE status IN ('SUCCESS', 'FAILED')), 0), 1) AS success_rate_pct,
       round(avg(EXTRACT(EPOCH FROM (finished_at - started_at)) * 1000) FILTER (WHERE finished_at IS NOT NULL)) AS avg_ms
FROM workflow_runs
GROUP BY ROLLUP (trigger_type)
ORDER BY trigger_type NULLS LAST;

\echo '## 5. 노드 실행 (node_type별, 성공률 = SUCCESS/(SUCCESS+FAILED))'
SELECT COALESCE(node_type, '(전체)') AS node_type,
       count(*) AS node_runs,
       count(*) FILTER (WHERE status = 'SUCCESS') AS success,
       count(*) FILTER (WHERE status = 'FAILED') AS failed,
       count(*) FILTER (WHERE status = 'SKIPPED') AS skipped,
       round(100.0 * count(*) FILTER (WHERE status = 'SUCCESS')
             / NULLIF(count(*) FILTER (WHERE status IN ('SUCCESS', 'FAILED')), 0), 1) AS success_rate_pct,
       round(avg(duration_ms)) AS avg_ms,
       round((percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms))::numeric) AS p50_ms,
       round((percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms))::numeric) AS p95_ms
FROM node_runs
GROUP BY ROLLUP (node_type)
ORDER BY node_type NULLS LAST;

\echo '## 6-1. 재처리 (workflow_runs)'
SELECT count(*) FILTER (WHERE retried_by_execution_id IS NOT NULL) AS reprocessed_originals,
       count(*) FILTER (WHERE retry_exhausted) AS retry_exhausted_runs
FROM workflow_runs;

\echo '## 6-2. 노드 재시도 (node_runs.attempt_count)'
SELECT count(*) FILTER (WHERE attempt_count > 1) AS node_runs_retried,
       COALESCE(sum(attempt_count - 1) FILTER (WHERE attempt_count > 1), 0) AS extra_attempts,
       max(attempt_count) AS max_attempts
FROM node_runs;

\echo '## 7-1. AI 노드 토큰 (node_runs, AI 노드만 채워짐 — 비용 칼럼 없음)'
SELECT count(*) AS ai_node_runs,
       count(*) FILTER (WHERE total_tokens IS NOT NULL) AS with_usage,
       sum(prompt_tokens) AS prompt_tokens,
       sum(completion_tokens) AS completion_tokens,
       sum(total_tokens) AS total_tokens
FROM node_runs WHERE node_type = 'AI';

\echo '## 7-2. 채팅 메시지·토큰 (chat_messages)'
SELECT COALESCE(sender_type, '(전체)') AS sender_type, count(*) AS messages,
       sum(input_tokens) AS input_tokens, sum(output_tokens) AS output_tokens
FROM chat_messages GROUP BY ROLLUP (sender_type) ORDER BY sender_type NULLS LAST;

\echo '## 8. 채팅 세션'
SELECT (SELECT count(*) FROM chat_sessions) AS sessions,
       (SELECT count(DISTINCT workflow_id) FROM chat_sessions) AS workflows_with_chat,
       round((SELECT count(*) FROM chat_messages)::numeric / NULLIF((SELECT count(*) FROM chat_sessions), 0), 2) AS avg_messages_per_session;

\echo '## 9. 사용자 (익명 집계)'
SELECT (SELECT count(*) FROM users) AS users_total,
       (SELECT count(DISTINCT user_id) FROM workflows) AS users_with_workflow,
       (SELECT count(DISTINCT w.user_id) FROM workflow_runs r JOIN workflows w ON w.id = r.workflow_id) AS users_with_run,
       (SELECT count(DISTINCT user_id) FROM chat_sessions) AS users_with_chat;

\echo '## 10. 노드 테스트·앱 트리거 구독·웹훅 토큰'
SELECT 'node_test_samples' AS src, status::text AS k, count(*) FROM node_test_samples GROUP BY 2
UNION ALL SELECT 'app_trigger_subscriptions', trigger_type::text, count(*) FROM app_trigger_subscriptions GROUP BY 2
UNION ALL SELECT 'workflow_webhook_tokens', 'tokens', count(*) FROM workflow_webhook_tokens
ORDER BY 1, 2;
METRICS_SQL
psqlq < "$B/metrics.sql" > "$B/metrics_pg.txt"; cat "$B/metrics_pg.txt"
```

### 7-2 Mongo 집계: 노드 수(type별·옛 템플릿 종류별)

옛 템플릿 종류는 이 전환으로 사라지는 옛 필드에서 계산한다. TRIGGER는 `config.triggerType`, ACTION은 `config.tools[0].name`, AI는 `config.agentType`이다. "최신 버전 기준"은 워크플로우마다 마지막 버전의 정의만 센다(PG `mongo_definition_id` ↔ Mongo `_id`). "전체 버전 기준"은 모든 버전을 센다.

```bash
echo "SELECT DISTINCT ON (workflow_id) mongo_definition_id FROM workflow_versions ORDER BY workflow_id, version DESC;" \
  | psqlq -At > "$B/latest_ids.txt"
wc -l "$B/latest_ids.txt"

cat > "$B/metrics_nodes_body.js" <<'NODES_JS'
const ids = raw.map(s => ObjectId(s)).concat(raw);   // _id가 ObjectId든 문자열이든 잡는다
const kind = {$switch: {branches: [
  {case: {$eq: ["$nodes.type", "TRIGGER"]}, then: {$concat: ["TRIGGER:", {$toString: {$ifNull: ["$nodes.config.triggerType", "(없음)"]}}]}},
  {case: {$eq: ["$nodes.type", "ACTION"]}, then: {$concat: ["ACTION:", {$toString: {$ifNull: [{$arrayElemAt: ["$nodes.config.tools.name", 0]}, "(없음)"]}}]}},
  {case: {$eq: ["$nodes.type", "AI"]}, then: {$concat: ["AI:", {$toString: {$ifNull: ["$nodes.config.agentType", "(없음)"]}}]}}
], default: {$toString: {$ifNull: ["$nodes.type", "(type 없음)"]}}}};
function report(title, match) {
  print("## " + title);
  print("definitions\t" + db.workflow_definitions.countDocuments(match));
  const base = [{$match: match}, {$unwind: "$nodes"}];
  const n = db.workflow_definitions.aggregate(base.concat([{$count: "n"}])).toArray();
  print("nodes\t" + (n.length ? n[0].n : 0));
  print("-- type별");
  db.workflow_definitions.aggregate(base.concat([{$group: {_id: "$nodes.type", n: {$sum: 1}}}, {$sort: {n: -1}}]))
    .forEach(r => print(r._id + "\t" + r.n));
  print("-- 옛 템플릿 종류별");
  db.workflow_definitions.aggregate(base.concat([{$group: {_id: kind, n: {$sum: 1}}}, {$sort: {n: -1}}]))
    .forEach(r => print(r._id + "\t" + r.n));
}
report("최신 버전 기준", {_id: {$in: ids}});
report("전체 버전 기준", {});
NODES_JS
{ printf 'const raw = ['; sed 's/.*/"&",/' "$B/latest_ids.txt" | tr -d '\n'; printf '];\n'; cat "$B/metrics_nodes_body.js"; } > "$B/metrics_nodes.js"
mongojs "$MDB_WF" "$B/metrics_nodes.js" > "$B/metrics_mongo_nodes.txt"; cat "$B/metrics_mongo_nodes.txt"
```

### 7-3 Mongo 집계: agent 세션·로그(모델별 토큰)

```bash
cat > "$B/metrics_agent.js" <<'AGENT_JS'
print("agent_sessions\t" + db.agent_sessions.countDocuments({}));
print("chat_logs\t" + db.chat_logs.countDocuments({}));
print("generate_workflow_logs\t" + db.generate_workflow_logs.countDocuments({}));
print("-- execution_logs 모델별 (AI 노드 실행, usage = agent 응답 그대로)");
db.execution_logs.aggregate([
  {$group: {_id: {provider: "$provider", model: "$model"},
            runs: {$sum: 1}, ok: {$sum: {$cond: ["$success", 1, 0]}},
            promptTokens: {$sum: {$ifNull: ["$usage.promptTokens", 0]}},
            completionTokens: {$sum: {$ifNull: ["$usage.completionTokens", 0]}},
            totalTokens: {$sum: {$ifNull: ["$usage.totalTokens", 0]}},
            avgMs: {$avg: "$durationMs"},
            first: {$min: "$createdAt"}, last: {$max: "$createdAt"}}},
  {$sort: {runs: -1}}
]).forEach(r => print(JSON.stringify(r)));
AGENT_JS
mongojs "$MDB_AG" "$B/metrics_agent.js" > "$B/metrics_mongo_agent.txt"; cat "$B/metrics_mongo_agent.txt"
```

- **비용**: PG·Mongo에는 비용 칼럼이 없다. 남은 것은 토큰뿐이다(PG `node_runs.prompt_tokens/completion_tokens/total_tokens`, `chat_messages.input_tokens/output_tokens`, Mongo `execution_logs.usage`). 비용은 Phoenix(`ieum-green-phoenix` :6006) span 속성 `ieum.cost_usd`에만 있다. Phoenix는 이번에 지우지 않으므로 필요할 때 기간 필터로 따로 본다. 보고서에는 "비용: Phoenix 별도"라고 적는다.

### 7-4 노트북으로 옮기고 보고서 쓰기

```bash
# 서버
cd "$B" && sha256sum * > SHA256SUMS
# 노트북 (D는 서버와 같은 값). 호스트 별칭은 Orca SSH 대상(kwung@kwungserver)과 같다
D=<서버의 D>
mkdir -p ~/Backups && scp -r "kwung@kwungserver:ieum-dev-backup-$D" ~/Backups/ieum-dev-$D
chmod 700 ~/Backups/ieum-dev-$D && cd ~/Backups/ieum-dev-$D && shasum -a 256 -c SHA256SUMS
```
- 백업은 레포에 넣지 않는다. 사용자 데이터와 크레덴셜 참조가 들어 있을 수 있다.
- 서버 사본 `~/ieum-dev-backup-$D`는 **아직 지우지 않는다.** §8(가드·`delete.sql`)·§10 불변식·§11 롤백이 `$B`를 쓴다. 지우는 시점은 §10 끝(e2e 통과로 롤백 창이 닫힌 뒤)이다.

보고서는 집계 출력과 쿼리를 그대로 싣는다(재현 가능):

```bash
cd ~/Backups/ieum-dev-$D
OUT="$HOME/Documents/ObsidianVault/프로젝트/진행중/IEUM/90-작업노트/dev-데이터-지표-$D.md"
{
  printf '# dev 데이터 지표 요약 (%s, 노드 event 전환 배포 직전)\n\n' "$D"
  printf -- '- 원본 백업: `~/Backups/ieum-dev-%s/` (pg_<테이블>.sql, mongo_*.jsonl, SHA256SUMS)\n' "$D"
  printf -- '- 기준: 옛 BE·agent 정지 후, 새 코드 배포 전(옛 코드가 만든 데이터만). 시각은 앱 LocalDateTime(KST)\n'
  printf -- '- 비용: PG·Mongo 칼럼 없음 — Phoenix `ieum.cost_usd` 별도\n'
  printf -- '- 관련: IEUM-BE-92, IEUM-AI-78, spec 2026-10-13-node-event-catalog-design §6\n\n'
  printf '## 삭제 대상 행 수\n```\n'; cat counts_before_del.txt; printf '```\n\n'
  for f in metrics_pg.txt metrics_mongo_nodes.txt metrics_mongo_agent.txt; do
    printf '## 결과: %s\n```\n' "$f"; cat "$f"; printf '```\n\n'
  done
  printf '## 쿼리\n'
  for f in metrics.sql metrics_nodes_body.js metrics_agent.js; do
    printf '### %s\n```\n' "$f"; cat "$f"; printf '```\n\n'
  done
} > "$OUT"
```

## 8. 삭제 (쓰기 — 사용자 확인 후, §6 백업 검증 뒤, 머지·배포 전)

§7 직후, **옛 BE·agent 컨테이너가 멈춘 상태(§5)에서, 머지·배포(§1-1~3) 전에** 실행한다. 컨테이너는 따로 멈추거나 띄우지 않지만 사용자 확인은 받는다. 멈춘 상태라 지우는 동안 Quartz·잡 워커·Gmail 폴링이 돌지 않는다. 지운 잡 스트림(`ieum:exec:jobs`)과 그룹 `runners`는 새 BE가 기동할 때 `ExecutionJobQueueBootstrap`이 새로 만들고, `ScheduleJobRestorer`는 빈 `workflows`라 복구할 Job이 없다(§9에서 확인). §3 셸 변수(`B`·`psqlq`·`mongojs`·`MDB_*`)가 그 사이 셸을 닫아 사라졌다면 §3 블록과 §4-4의 `MDB_WF`·`MDB_AG` 대입을 다시 실행한다. 다시 실행할 때 §3 첫 줄은 `D=<백업 날짜>`로 고정한다(`date +%F`를 다시 계산하면 자정을 넘긴 경우 빈 폴더를 가리킨다).

먼저 백업 폴더를 확인한다. `backup-ok`가 나와야 진행한다(없으면 멈춘다 — `$B` 폴더가 없거나 비었으면(자정 넘겨 D 재계산) 아래 삭제 체인이 맨 앞 검사에서 멈춘다. 어느 쪽이든 PG·Mongo·Redis는 지우지 않는다):
```bash
[ -d "$B" ] && [ -s "$B/counts_before_keep.txt" ] && echo backup-ok
```

```bash
cat > "$B/delete.sql" <<'DELETE_SQL'
BEGIN;
-- CASCADE 없음: §0의 9개 밖 테이블이 FK로 가리키면 실패하고 아무것도 지우지 않는다(그때는 멈추고 §4-2 결과를 사용자에게 보인다).
-- 목록은 FK 자식 → 부모 순서(node_runs → workflow_runs → … → workflows)다. 한 문장이라 순서와 무관하게 성공한다.
TRUNCATE TABLE node_runs, workflow_runs, node_test_samples, app_trigger_subscriptions,
               workflow_webhook_tokens, chat_messages, chat_sessions, workflow_versions, workflows;
COMMIT;
DELETE_SQL
cat > "$B/delete_wf.js" <<'DEL_WF_JS'
print("workflow_definitions deleted\t" + db.workflow_definitions.deleteMany({}).deletedCount);
DEL_WF_JS
cat > "$B/delete_ag.js" <<'DEL_AG_JS'
print("agent_sessions deleted\t" + db.agent_sessions.deleteMany({}).deletedCount);
DEL_AG_JS
# PG → Mongo → Redis. 앞 단계가 실패하면 뒤(특히 Redis DEL)는 돌지 않는다
[ "$(hostname -s)" = kwungserver ] &&           # 노트북이면 여기서 멈춘다
[ -n "$MDB_WF" ] && [ -n "$MDB_AG" ] &&                 # §4-4 대입이 없으면 멈춘다
[ "$(for t in $TABLES_DEL; do [ -s "$B/pg_$t.sql" ] && echo y; done | wc -l)" -eq "$(echo $TABLES_DEL | wc -w)" ] &&   # 삭제 대상 테이블마다 PG 백업 파일이 있어야 한다
[ -z "$(grep -L '^COPY public\.' "$B"/pg_*.sql)" ] &&   # COPY 블록 없는 PG 백업이 있으면 멈춘다
[ -s "$B/counts_before_keep.txt" ] &&   # 빈 새 폴더(자정 넘겨 D 재계산)면 여기서 멈춘다
psqlq < "$B/delete.sql" &&
mongojs "$MDB_WF" "$B/delete_wf.js" &&  # 숫자 = wc -l mongo_workflow_definitions.jsonl
mongojs "$MDB_AG" "$B/delete_ag.js" &&  # 숫자 = wc -l mongo_agent_sessions.jsonl
docker exec ieum-redis redis-cli DEL ieum:exec:jobs &&
docker exec ieum-redis sh -c 'for k in $(redis-cli --scan --pattern "webhook:listen:*"); do redis-cli DEL "$k"; done' &&
echo delete-done                        # 안 나오면 멈추고 어느 단계가 실패했는지 사용자에게 보인다

counts "$TABLES_DEL"  > "$B/counts_after_del.txt";  cat "$B/counts_after_del.txt"     # 전부 0
counts "$TABLES_KEEP" > "$B/counts_after_keep.txt"; diff "$B/counts_before_keep.txt" "$B/counts_after_keep.txt" && echo keep-unchanged
```

## 9. 배포 후 확인 (읽기 — 재시작하지 않는다)

§1-3 배포로 뜬 새 컨테이너를 그대로 본다. 데이터는 §8에서 이미 비웠으므로 재시작할 이유가 없다.

```bash
for i in $(seq 1 30); do curl -fsS http://10.10.10.11:8080/actuator/health/readiness >/dev/null 2>&1 && { echo be-ready; break; }; sleep 2; done
curl -fsS http://10.10.10.14:8000/health && echo agent-ok
docker logs ieum-backend 2>&1 | grep -E 'ScheduleJobRestorer|ExecutionJobQueueBootstrap|EventCatalog|ERROR' | tail -20   # 새 컨테이너라 전체 로그 = 이번 기동
#  기대: "[ScheduleJobRestorer] 복구할 스케줄 Job 없음", "[ExecutionJobQueueBootstrap] 잡 큐 기동 완료 — stream: ieum:exec:jobs, group: runners"
#  카탈로그(EventCatalog) 로드 실패는 기동 실패라 be-ready가 나오지 않는다
docker exec ieum-redis redis-cli XINFO GROUPS ieum:exec:jobs    # §8에서 지운 스트림이 그룹 runners와 함께 새로 생김(MKSTREAM)
```

## 10. dev e2e 체크리스트

FE 빌더는 아직 event를 보내지 않는다. 그래서 API를 직접 부른다. accessToken은 dev FE(`https://green.kiacms.co.kr/main`)에 로그인한 탭의 localStorage `accessToken`이다. 만료되면 `POST /api/v1/auth/refresh`에 body `{refreshToken}`을 보낸다(메모리 `reference-dev-db-access`).

```bash
BASE=https://green.kiacms.co.kr/api/v1
AT=<accessToken>
H=(-H "Authorization: Bearer $AT" -H 'Content-Type: application/json')
T1=a1111111-0000-4000-8000-000000000001   # 트리거 노드 id
N2=a1111111-0000-4000-8000-000000000002   # 두 번째 노드 id
body() {  # 사용: body <이름> <트리거 event> <트리거 config JSON> [최상위 추가 필드 JSON 조각]
  printf '{"name":"%s","nodes":[{"id":"%s","event":"%s","label":"트리거","position":{"x":0,"y":0},"config":%s},{"id":"%s","event":"transform.map","label":"변환","position":{"x":240,"y":0},"config":{"mappings":{"hello":"world"}}}],"edges":[{"id":"e2e-edge-1","source":"%s","target":"%s"}]%s}' \
    "$1" "$T1" "$2" "$3" "$N2" "$T1" "$N2" "${4:+,$4}"
}
```

- [ ] **E1 빈 워크플로우 생성**: `curl -sS "${H[@]}" -X POST $BASE/workflows -d '{"name":"e2e-empty","nodes":[],"edges":[],"triggerType":"MANUAL"}'` → `success:true`, `data.triggerType=MANUAL`, `data.cronExpression=null`. `data.id`를 `WF_EMPTY`로 적는다. **E4는 E1 생성 후 30분 안에 한다** — `WorkflowCleanupScheduler.cleanupOrphanWorkflows`(10분마다)가 생성 30분이 지났고 최대 버전 ≤1이며 최신 버전 노드가 빈 워크플로우를 하드 삭제한다. 늦었으면 E1을 다시 한다.
- [ ] **E2 수동 실행 + 파생**: `curl -sS "${H[@]}" -X POST $BASE/workflows -d "$(body e2e-manual manual.run '{}')"` → 응답 노드의 `type`이 `TRIGGER`·`TRANSFORM`(요청에 type 없음)이고, `config.brand`가 `webhook`·`filter`(apps.json manual·transform)이며, `data.triggerType=MANUAL`이다. `WF_MANUAL`로 적고 `curl -sS "${H[@]}" -X POST $BASE/workflows/$WF_MANUAL/execute` → `GET $BASE/workflows/$WF_MANUAL/executions`에서 `SUCCESS`, trigger_type `MANUAL`.
- [ ] **E3 거부 4종** (모두 HTTP 400. 메시지 원문은 `NodeDerivation`·`TriggerNodeSchedule`·`NodeDto`):
  - 최상위 불일치: `body e2e-bad manual.run '{}' '"triggerType":"WEBHOOK"'` → `INVALID_WORKFLOW`, "요청의 triggerType(WEBHOOK)이 노드에서 계산한 값과 다릅니다. 계산값: triggerType=MANUAL — 최상위 값은 보내지 않거나 계산값과 같게 보내세요."
  - 모르는 event: `body e2e-bad nope.nope '{}'` → `INVALID_WORKFLOW`, "알 수 없는 노드 종류입니다: nope.nope (노드 a1111111-0000-4000-8000-000000000001)"
  - 실행할 수 없는 event(카탈로그엔 있으나 ACTION인데 `builder:false`): 두 번째 노드를 `{"event":"gmail.send_email",…}`로 바꾼 본문 → `INVALID_WORKFLOW`, "실행할 수 없는 노드 종류입니다: gmail.send_email (노드 a1111111-0000-4000-8000-000000000002)"
  - event 누락: 트리거 노드에서 `"event"` 키를 뺀 본문 → `INVALID_INPUT`, "노드 event는 필수입니다."
  - 메시지의 event·노드 id는 제어·서식·줄 구분 문자(`[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]`)를 `?`로 바꾸고 64자에서 자른 값이다. 실행 가능 판정은 ACTION이면 `builder`, 그 밖은 `builder || generation`이다.
  - (채팅) `currentNodes` 원소가 객체가 아니면 400 `INVALID_WORKFLOW` "currentNodes의 원소는 노드 객체여야 합니다." — 쿼터 예약·agent 호출 전에 막는다.
- [ ] **E4 채팅 생성**: `curl -sS "${H[@]}" -X POST $BASE/workflows/$WF_EMPTY/chat -d '{"prompt":"매일 오전 9시에 hello라는 값을 만드는 워크플로우를 만들어줘"}'` → 성공. 이어서 `echo "SELECT trigger_type, cron_expression FROM workflows WHERE id='$WF_EMPTY';" | psqlq`(서버) → 생성된 첫 트리거와 일치한다. 트리거가 없으면 `MANUAL`·null이다. 그다음 아래 **불변식 점검**에서 0건이어야 한다. 끝나면 `curl -sS "${H[@]}" -X POST $BASE/workflows/$WF_EMPTY/deactivate`로 끈다(생성된 SCHEDULE 트리거가 계속 발화하지 않게).
- [ ] **E5 스케줄**: `body e2e-schedule schedule.every '{"cron":"*/5 * * * *"}'`로 생성 → `data.triggerType=SCHEDULE`, `data.cronExpression`은 Quartz 형식. 같은 본문에 `'"cronExpression":"0 9 * * *"'`를 붙이면 400이다. 5분 안에 `executions`에 trigger_type `SCHEDULE` 실행이 SUCCESS로 생긴다. 확인한 뒤 `POST $BASE/workflows/<id>/deactivate`.
- [ ] **E6 ACTION 합성 도구(슬랙 웹훅 주입)**: 등록된 Slack 웹훅이 있을 때만 한다. `GET $BASE/integrations/slack/options/webhooks`에서 값을 고른다. 트리거 `manual.run` + 두 번째 노드를 `{"event":"slack.send_message","config":{"webhookCredentialId":"<값>","message":"ieum e2e"}}`로 바꿔 생성·실행 → Slack 채널에 메시지가 오고, `node_runs`의 ACTION 행이 SUCCESS다. 응답 노드 config에 `tools`가 없어야 한다.
- [ ] **E7 웹훅**: `body e2e-webhook webhook.catch '{}'`로 생성(`WF_HOOK`) → `POST $BASE/workflows/$WF_HOOK/webhook-token` → `data.path`(`/api/v1/hooks/<token>`). 노드 테스트 `POST $BASE/workflows/$WF_HOOK/nodes/$T1/test` → `data.status=LISTENING`. `curl -sS -X POST https://green.kiacms.co.kr<path> -H 'Content-Type: application/json' -d '{"hi":1}'` → 활성 WEBHOOK 워크플로우는 listen 중이어도 **첫 요청도 실행한다**(`WebhookService`: 샘플을 남기고, 활성·triggerType 일치면 이어서 실행). 첫 응답이 `{ok:true, executionId:"…"}`, `GET $BASE/workflows/$WF_HOOK/nodes/$T1/sample`의 출력이 `{"hi":1}`, 그 실행의 트리거 출력도 `{"hi":1}`이다. 샘플만 확인하려면 `POST $BASE/workflows/$WF_HOOK/deactivate` 뒤 다시 listen하고 보낸다 → 응답 `executionId: null`(샘플만 저장, 실행 없음).
- [ ] **E8 Gmail 트리거**: dev에 Google 연동 계정이 있을 때만 한다. `body e2e-gmail gmail.new_email '{"query":"subject:ieum-e2e"}'`로 생성 → `SELECT trigger_type, resource_key FROM app_trigger_subscriptions;`에 `GMAIL_NEW_EMAIL` 1행. 노드 테스트(`.../nodes/$T1/test`)는 즉시 SUCCESS 또는 FAILED "최근 항목이 없습니다"다. 연동 계정으로 제목 `ieum-e2e` 메일을 받으면 2~3분 안에 trigger_type `GMAIL_NEW_EMAIL` 실행이 생긴다. 확인한 뒤 deactivate한다.
- [ ] **E9 GitHub 트리거**: GitHub App이 설치돼 있을 때만 한다. `GET $BASE/integrations/github/options/installed_repos`에서 값을 고르고 `body e2e-github github.new_issue '{"repoId":"<값>"}'`로 생성 → 구독 행 `GITHUB_NEW_ISSUE`. 그 저장소에 이슈를 만들면 실행이 생긴다. 확인한 뒤 deactivate한다.
- [ ] **E10 목록 아이콘**: `GET $BASE/workflows` → e2e-gmail은 `services`에 `GMAIL`, e2e-github는 `GITHUB`, e2e-webhook은 `WEBHOOK`이 있다. e2e-schedule·e2e-manual은 transform만 있으므로 `services`가 빈 목록이다.
- [ ] **E11 채팅 수정 왕복**: 저장된 노드·엣지를 그대로 `currentNodes`·`currentEdges`로 실어 채팅으로 고친다(BE가 currentNodes를 파생해 agent로 넘긴다).
  ```bash
  W=$(curl -sS "${H[@]}" $BASE/workflows/$WF_MANUAL | jq '.data')
  curl -sS "${H[@]}" -X POST $BASE/workflows/$WF_MANUAL/chat \
    -d "$(jq -n --argjson w "$W" '{prompt:"변환 노드의 hello 값을 bye로 바꿔줘", currentNodes:$w.nodes, currentEdges:$w.edges}')" \
    | jq '{success, type: .data.type, nodes: [.data.nodes[] | {id, event, type}]}'
  ```
  → `success:true`, `type=WORKFLOW_MODIFIED`, 응답 노드마다 `event`·`type`이 있다. 이어서 아래 **불변식 점검**이 0건이어야 한다.
- [ ] **E12 검증 경로**: 필수 칸이 빈 ACTION 노드로 생성하고 정적 검증을 부른다.
  ```bash
  WF_VAL=$(curl -sS "${H[@]}" -X POST $BASE/workflows -d "{\"name\":\"e2e-validate\",\"nodes\":[{\"id\":\"$T1\",\"event\":\"manual.run\",\"label\":\"트리거\",\"position\":{\"x\":0,\"y\":0},\"config\":{}},{\"id\":\"$N2\",\"event\":\"github.create_issue\",\"label\":\"이슈\",\"position\":{\"x\":240,\"y\":0},\"config\":{\"owner\":\"ieum\"}}],\"edges\":[{\"id\":\"e2e-edge-1\",\"source\":\"$T1\",\"target\":\"$N2\"}]}" | jq -r '.data.id')
  curl -sS "${H[@]}" -X POST $BASE/workflows/$WF_VAL/validate | jq '{valid: .data.valid, required: [.data.errors[] | select(.code=="REQUIRED") | .field]}'
  ```
  → 생성은 성공하고, `valid=false`, `required`에 `"config.repo"`·`"config.title"`이 있다(둘 다 `nodeId`=`$N2`). agent가 응답하지 않으면 502 `PROVIDER_ERROR`다.
- [ ] **불변식 점검** (E1~E12를 마친 뒤, 서버):
  ```bash
  cat > "$B/invariant.js" <<'INV_JS'
  const bad = db.workflow_definitions.aggregate([
    {$unwind: "$nodes"},
    {$match: {$or: [
      {"nodes.event": {$exists: false}},
      {"nodes.config.triggerType": {$exists: true}},
      {"nodes.config.serviceType": {$exists: true}},
      {"nodes.type": "ACTION", "nodes.config.tools": {$exists: true}}
    ]}},
    {$project: {_id: 1, "nodes.id": 1, "nodes.event": 1, "nodes.type": 1}}
  ]).toArray();
  print("violations\t" + bad.length); bad.forEach(b => print(JSON.stringify(b)));
  INV_JS
  mongojs "$MDB_WF" "$B/invariant.js"    # violations 0
  ```
- [ ] e2e 워크플로우는 남겨 두거나 `DELETE $BASE/workflows/<id>`로 지운다(사용자가 정한다).
- [ ] 서버 백업 사본 정리(쓰기 — 사용자 확인 후): e2e를 통과해 롤백 창이 닫힌 뒤에만, 노트북 사본(§7-4 체크섬 통과)이 있는 것을 확인하고, `D=<백업 날짜>`로 고정한 셸에서 `rm -rf ~/ieum-dev-backup-$D`. 그 전에는 §8·§10·§11이 `$B`를 쓰므로 지우지 않는다.

## 11. 롤백

데이터는 이미 새 모양이다(event만 있는 노드). 옛 코드는 이 노드를 읽지 못하므로 **새 데이터를 다시 지우고** 옛 이미지로 돌린다. 옛 데이터는 §8에서 배포 전에 지웠으므로, 옛 이미지를 다시 띄우면 아래 4(복원)를 하지 않는 한 빈 데이터로 뜬다(배포가 실패해 §8 직후 옛 이미지로 돌아가는 경우도 같다).

1. §5처럼 `docker stop ieum-backend ieum-agent`.
2. 서버 사본(`~/ieum-dev-backup-$D`)을 §10 끝에서 이미 지웠다면 먼저 아래 4의 노트북 → 서버 `scp` 되올리기·체크섬·셸 준비를 한다(§8 삭제 체인은 `$B`가 있어야 돈다). 그다음 §8의 PG·Mongo·Redis 삭제를 다시 실행한다. 새 데이터는 e2e뿐이므로 백업하지 않는다.
3. 옛 이미지로 재배포한다. 머지 직전의 성공한 dev 배포 run을 다시 돌린다. rerun은 원래 커밋으로 빌드하므로 카탈로그 서브모듈도 옛 상태로 돌아간다.
   ```bash
   gh run list --repo team-ieum/ieum-agent   --workflow agent-dev-server.yml   --branch dev --status success --limit 5
   gh run list --repo team-ieum/ieum-backend --workflow backend-dev-server.yml --branch dev --status success --limit 5
   gh run rerun <머지 직전 agent run id> --repo team-ieum/ieum-agent
   gh run rerun <머지 직전 BE run id>    --repo team-ieum/ieum-backend
   ```
   다음 dev push가 새 코드를 다시 배포한다. 롤백을 유지하려면 두 레포 dev에서 머지 커밋을 revert한다(사용자 결정).
4. (선택) 옛 데이터를 복원한다. 노트북 사본을 서버에 다시 올리고, 서버에서 체크섬을 확인하고 셸을 다시 준비한 뒤 부모 → 자식 순서로 복원한다.
   ```bash
   # 노트북 — 서버 사본(~/ieum-dev-backup-$D)이 아직 남아 있으면 건너뛴다(있는 폴더에 scp -r하면 하위 폴더로 들어간다)
   D=<백업 날짜>
   scp -r ~/Backups/ieum-dev-$D "kwung@kwungserver:ieum-dev-backup-$D"

   # 서버 — 체크섬이 전부 OK여야 다음으로 간다
   (cd ~/ieum-dev-backup-<백업 날짜> && sha256sum -c SHA256SUMS)
   # §3 블록을 다시 실행한다(D·B·TABLES_*·psqlq·mongojs·counts). 단 첫 줄은 D=<백업 날짜>로 바꿔 B가 올린 폴더를 가리키게 한다
   # §4-4 블록을 다시 실행해 MDB_WF·MDB_AG를 정한다

   # 복원 (서버)
   for t in workflows workflow_versions workflow_runs node_runs node_test_samples app_trigger_subscriptions workflow_webhook_tokens chat_sessions chat_messages; do
     psqlq < "$B/pg_$t.sql"
   done
   docker cp "$B/mongo_workflow_definitions.jsonl" ieum-mongo:/tmp/wd.jsonl
   docker exec -e MDB="$MDB_WF" ieum-mongo sh -c 'mongoimport --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin -d "$MDB" -c workflow_definitions --file /tmp/wd.jsonl'
   docker cp "$B/mongo_agent_sessions.jsonl" ieum-mongo:/tmp/as.jsonl
   docker exec -e MDB="$MDB_AG" ieum-mongo sh -c 'mongoimport --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin -d "$MDB" -c agent_sessions --file /tmp/as.jsonl'
   docker exec ieum-mongo rm -f /tmp/wd.jsonl /tmp/as.jsonl
   ```
   복원한 뒤 BE를 재기동해야 `ScheduleJobRestorer`가 스케줄 Job을 다시 만든다.

## 12. 워크스페이스 계약 문서 갱신 (e2e 통과 후)

`/Users/dobee/WorkSpace/IEUMWorkSpace/CLAUDE.md` "크로스레포 데이터 계약"의 세 줄을 바꾸고 한 줄(AI 노드 event 선택)을 더한다. git 밖 파일이므로 커밋하지 않는다. 행 번호는 2026-10-18 기준이고 한 줄을 더하면 밀리므로 내용으로 찾는다.

- 29행 `워크플로우 JSON 스키마` 줄 끝에 덧붙인다:
  `노드 = {id, event, type, label, description, position, config}: event(카탈로그 앱.이벤트, 원본 ieum-node-catalog)가 유일한 정체성이고 필수(BE 400·agent 422), type은 서버가 event의 node_type으로 계산(요청 값 무시). config에 triggerType·serviceType 없음, ACTION은 tools 없이 입력 칸 config.<칸>(도구 = event의 tool_key). 트리거 종류 = 첫 TRIGGER 노드 event의 trigger_type, BE 요청 최상위 triggerType·cronExpression은 검증용(null 통과, 계산값과 다르면 400).` (각 이름은 백틱으로 감싼다)
- 36행 `AI 노드 출력` 줄 앞부분 `AI 노드 출력 = `을 바꾼다:
  `AI 노드 = event ai.agent|ai.reasoning(프리셋 출처는 저장 안 함, 칸 규칙만 presets.json match로 고름). AI 노드 출력 = `
  그리고 이 줄 바로 아래에 한 줄을 추가한다: `- **AI 노드 event 선택** — 도구 없는 simple AI = ai.reasoning, 도구가 있거나 프리셋 = ai.agent.`
- 37행 `field=카탈로그 칸 path`를 바꾼다:
  `field=카탈로그 칸 path — ACTION config.<칸>, AI 프리셋 도구 config.tools.0.config.<칸>, 트리거 config.cron`

## 부록. 계약 메모

- **순서(spec §6 대비)**: spec §6은 "배포 → 보존 → 삭제"다. 이 런북은 보존과 삭제를 모두 배포 앞으로 당긴다: 옛 BE·agent 정지(§5, Quartz·잡 큐·Gmail 폴링 정지를 행 변화로 확인) → 백업(§6) → 지표(§7) → 삭제(§8) → 확인·머지·배포(§1-1~3) → health 확인(§9) → e2e(§10). 백업·지표는 옛 코드가 만든 데이터만 담고, 새 코드는 빈 데이터로 처음 뜨므로 옛 SCHEDULE·Gmail 구독을 event 없는 노드(MANUAL 판정)로 도는 구간이 없다. 배포가 늦어지는 동안 dev는 멈춰 있다(사용자가 감수).
- **Quartz 테이블은 없다.** `SchedulerConfig`가 `RAMJobStore`를 쓴다. spec §6-3의 "Quartz Job 삭제"는 PG `workflows` 삭제 + 새 BE 기동(`ScheduleJobRestorer`가 0건 복구)으로 처리한다. **PG 실행 큐 테이블도 없다.** 큐는 Redis Stream `ieum:exec:jobs`(그룹 `runners`)다.
- 삭제 대상은 엔티티 `@Table` 15개 중 9개다. `docs/schema` V1~V10은 남기는 테이블과 CHECK만 다루고 새 테이블은 없다.
- **비용 칼럼은 PG·Mongo 어디에도 없다.** 토큰만 있다. 비용은 Phoenix `ieum.cost_usd`뿐이다.
- Mongo DB 이름(`MDB_WF`·`MDB_AG`)과 컨테이너의 root 인증 env 이름은 코드로 확정할 수 없다(BE `MONGO_URI`는 `.env`). 그래서 §4-4가 런타임에 확인한다. dev Mongo 컨테이너에 `MONGO_INITDB_ROOT_*` env가 없으면 §3의 `mongojs`와 mongoexport 인증 인자를 바꿔야 한다.
- e2e E2·E10은 `NodeDerivation.derive`(요청 `type` 무시·event의 `node_type`으로 덮기, 비AI 노드 `config.brand` = `apps.json` brand: manual·schedule·webhook·http·approval → `webhook`, branch·transform → `filter` — 카탈로그 `105975a`에서 확인)와 `WorkflowServiceType`(transform·schedule·manual → 아이콘 없음) 계약을 전제한다. 트리거 저장값은 `TriggerNodeSchedule`(첫 TRIGGER 노드 event → `trigger_type`, `assertCronPresent`·`assertMatchesRequest`)이 계산한다. E7의 "활성이면 첫 수신도 실행, 비활성·불일치면 샘플만·executionId null"은 `WebhookService.start`의 현재 동작이다.
