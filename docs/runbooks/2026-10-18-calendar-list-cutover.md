# Google Calendar `list` → `find` dev 데이터 처리 런북

- spec: `docs/superpowers/specs/2026-10-18-calendar-node-baseline-design.md` §7(데이터)·§8(배포 순서)
- 대상: dev 서버 `kwung@kwungserver`(Orca 환경 `IEUM_DEV`). 운영 DB는 없다.
- 접근·셸·호스트 가드·백업 방식은 `docs/runbooks/2026-10-13-node-event-cutover.md`(이하 "전환 런북") §2·§3·§5·§6을 그대로 쓴다. 다른 점은 **전체 TRUNCATE가 아니라 workflow_id로 거른 삭제**와 **AI 도구 이름 교체**다.
- **쓰기 명령(§3 정지, §6 삭제, §7 교체, §1의 머지·배포, §9 롤백)은 사용자가 확인한 뒤에만 실행한다.** 확인 지점은 본문에 `⏸ 사용자 확인`으로 표시했다. 읽기 명령(§2, §4 검증, §5 지표)은 바로 돌려도 된다.

## 0. 무엇을 지우고 무엇을 바꾸나

- **삭제(워크플로우 통째)**: Mongo `workflow_definitions`의 **어느 버전이든** `nodes.event == "google_calendar.list"`를 가진 워크플로우. `{{…output.events}}` 참조가 새 `find` 출력(`items`)에서 조용히 빈 값이 되므로 고치지 않고 지운다.
  - PG 9개 테이블에서 그 workflow_id의 행: `node_runs`(→`workflow_runs` 경유) · `workflow_runs` · `node_test_samples` · `app_trigger_subscriptions` · `workflow_webhook_tokens` · `chat_messages`(→`chat_sessions` 경유) · `chat_sessions` · `workflow_versions` · `workflows`. 앞의 7개 이름·칼럼은 엔티티 `@Table`로 확인했다(BE `f1e9c17`, 15개 테이블 그대로).
  - Mongo: 그 워크플로우의 **모든 버전** 문서(`workflow_versions.mongo_definition_id`) + PG 버전에 매달리지 않은 일치 문서.
- **교체(삭제 아님)**: 남은 문서의 AI 노드 `config.tools[].name == "builtin:google_calendar_list"` → `"builtin:google_calendar_find"`. 안 바꾸면 agent가 모르는 도구 이름을 조용히 버려 캘린더 없이 지어낸 출력으로 SUCCESS가 된다. 바인딩 칸 `calendar_id`·`max_results`는 이름이 같아 그대로 둔다.
- **손대지 않음**: Redis(`ieum:exec:jobs`에 지운 실행의 잡이 남아도 BE 워커가 "실행 레코드 없음 — 잡 폐기"로 ack), `webhook:listen:*`(TTL 5분), agent `agent_sessions`(ADK 세션, TTL), 그 밖의 PG 6개 테이블.

## 1. 진행 순서 (spec §8)

- [ ] 1. **BE PR 1(드롭다운) 머지·배포**(⏸ 사용자 확인 — 머지 = dev 자동 배포). 배포 뒤 dev에서 `GET /api/v1/integrations/google/options/writable_calendars`를 **Bearer 토큰을 붙여**(전환 런북 §10의 `AT`. 토큰 없이 부르면 401이라 이 확인이 거짓으로 통과한다) 부르고 404 `NOT_FOUND`("지원하지 않는 옵션 목록")가 아닌지 본다(200 또는 `GOOGLE_SCOPE_REQUIRED`/`ACCOUNT_NOT_CONNECTED`면 빈이 있다).
- [ ] 2. **카탈로그 PR 머지**(⏸ 사용자 확인). 노트북 BE 레포 루트에서 커밋을 적는다:
  ```bash
  git -C catalog fetch origin && CAT_SHA=$(git -C catalog rev-parse origin/main) && echo "$CAT_SHA"
  ```
- [ ] 3. **agent PR·BE PR 2의 서브모듈이 `$CAT_SHA`인지, agent PR에 새 도구 이름이 있는지**(읽기). `같음`·`tool-ok`가 나오고, 두 PR의 checks가 전부 pass + state OPEN + reviewDecision이 CHANGES_REQUESTED가 아닐 때만 4 이후(정지·삭제·교체)로 간다. 하나라도 아니면 멈춘다 — 데이터를 지운 뒤에 머지가 막히면 dev가 BE·agent 정지 상태로 멈춘다.
  ```bash
  AGENT_BR=<agent PR 브랜치>; BE_BR=<BE PR 2 브랜치>
  git fetch origin && git -C ../ieum-agent fetch origin
  test -n "$CAT_SHA" &&
  test "$(git -C ../ieum-agent rev-parse "origin/$AGENT_BR:catalog")" = "$CAT_SHA" &&
  test "$(git rev-parse "origin/$BE_BR:catalog")" = "$CAT_SHA" && echo 같음
  git -C ../ieum-agent grep -q '"builtin:google_calendar_find"' "origin/$AGENT_BR" -- tools/__init__.py && echo tool-ok   # §7이 바꿀 이름
  gh pr checks "$AGENT_BR" --repo team-ieum/ieum-agent
  gh pr checks "$BE_BR"    --repo team-ieum/ieum-backend
  gh pr view "$AGENT_BR" --repo team-ieum/ieum-agent   --json state,reviewDecision
  gh pr view "$BE_BR"    --repo team-ieum/ieum-backend --json state,reviewDecision
  ```
- [ ] 4. 두 레포 dev 머지 동결을 공지한다. **§2 셸 → §3 정지 → §4 백업 → §5 지표 → §6 삭제 → §7 교체**. 정지는 아래 5·6의 배포가 새 컨테이너를 띄울 때까지 유지한다.
- [ ] 5. **agent PR 머지**(⏸ 사용자 확인) → 배포 Health check 초록 뒤 서버에서:
  ```bash
  curl -fsS http://10.10.10.14:8000/health && echo agent-ok
  curl -fsS http://10.10.10.14:8000/v1/nodes/catalog | grep -o 'google_calendar\.\(find\|delete\|list\)' | sort -u   # find·delete만, list 없음
  ```
- [ ] 6. **BE PR 2(서브모듈) 머지**(⏸ 사용자 확인) → 배포 뒤 서버에서:
  ```bash
  for i in $(seq 1 30); do curl -fsS http://10.10.10.11:8080/actuator/health/readiness >/dev/null 2>&1 && { echo be-ready; break; }; sleep 2; done
  sleep 10   # 대조는 기동 뒤 별도 스레드에서 돌고, 일치하면 로그를 남기지 않는다 — 끝나기 전에 grep하면 거짓 no-catalog-diff
  docker logs ieum-backend 2>&1 | grep '\[CatalogDiff\]' || echo no-catalog-diff   # 배포는 컨테이너를 새로 만드므로 전체 로그 = 이번 기동
  ```
  `no-catalog-diff`가 나와야 한다. BE는 기동 때 **한 번만** agent와 대조한다 — warn이 있으면 agent가 새 카탈로그로 떠 있는지(5) 확인한 뒤 재시작하고(⏸ 사용자 확인) 다시 본다. `docker restart`는 같은 컨테이너라 이전 기동의 warn이 로그에 남으므로 재시작 시각 이후만 본다:
  ```bash
  T=$(date -u +%Y-%m-%dT%H:%M:%SZ); docker restart ieum-backend
  for i in $(seq 1 30); do curl -fsS http://10.10.10.11:8080/actuator/health/readiness >/dev/null 2>&1 && { echo be-ready; break; }; sleep 2; done
  sleep 10; docker logs --since "$T" ieum-backend 2>&1 | grep '\[CatalogDiff\]' || echo no-catalog-diff
  ```
  be-ready가 안 나오고 로그에 `optionsSource 공급원 없음: google.writable_calendars`가 있으면 BE PR 2 브랜치에 PR 1(드롭다운)이 들어 있지 않은 것이다.
- [ ] 7. spec §9 dev e2e(실계정). 끝나면 머지 동결 해제, §8 정리.

## 2. 셸 준비 (서버)

전환 런북 §2로 접속하고 §3 블록 대신 **아래 블록을 그대로 실행한다**(`B` 폴더 이름이 다르고, `TABLES_KEEP`은 없고, `set -o pipefail`이 더 있다). `on-server`가 나와야 진행한다(로컬 docker-compose도 컨테이너·env 이름이 같다).

```bash
[ "$(hostname -s)" = kwungserver ] && echo on-server
set -o pipefail                     # §6·§7의 `| tee`가 앞 명령 실패를 삼키지 않게
D=$(date +%F)
B=~/ieum-dev-backup-cal-$D
mkdir -p "$B" && chmod 700 "$B"
TABLES_DEL="node_runs workflow_runs node_test_samples app_trigger_subscriptions workflow_webhook_tokens chat_messages chat_sessions workflow_versions workflows"
psqlq() { docker exec -i ieum-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X -v ON_ERROR_STOP=1 "$@"' psql "$@"; }
mongojs() {  # 사용: mongojs <DB이름> <js파일>
  docker cp "$2" ieum-mongo:/tmp/runbook.js &&
  docker exec -e MDB="$1" ieum-mongo sh -c 'mongosh --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin "$MDB" --file /tmp/runbook.js'
}
counts() { for t in $1; do echo "SELECT '$t', count(*) FROM $t;"; done | psqlq -At; }
```
그다음 전환 런북 §4-4로 `MDB_WF`(workflow_definitions가 있는 DB)를 정한다. 전환 런북 §4-2(FK 점검)를 돌려 child가 위 9개 밖이면 멈추고 사용자에게 묻는다.

## 3. 멈춤 (⏸ 사용자 확인)

전환 런북 §5 그대로: 호스트 가드를 건 `docker stop ieum-backend ieum-agent`, `snap` 두 번(150초 간격) → `quiet`. 다르면 멈추고 사용자에게 묻는다.

## 4. 백업

```bash
counts "$TABLES_DEL" > "$B/counts_before.txt"
for t in $TABLES_DEL; do
  docker exec ieum-postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --data-only --table="public.$0"' "$t" > "$B/pg_$t.sql" \
    || echo "실패: $t"
done
docker exec -e MDB="$MDB_WF" ieum-mongo sh -c 'mongoexport --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin -d "$MDB" -c workflow_definitions --jsonFormat=canonical' > "$B/mongo_workflow_definitions.jsonl"
grep -L '^COPY public\.' "$B"/pg_*.sql      # 출력 없어야 한다
wc -l "$B/mongo_workflow_definitions.jsonl"  # = countDocuments
(cd "$B" && sha256sum pg_*.sql mongo_workflow_definitions.jsonl counts_before.txt > SHA256SUMS)   # 자기 자신은 넣지 않는다
```
노트북으로 옮기는 방법은 전환 런북 §7-4 첫 블록과 같다(폴더 이름만 `ieum-dev-backup-cal-$D`). 단 그 블록의 `# 서버` 줄(`sha256sum * > SHA256SUMS`)은 **건너뛴다** — 위에서 이미 만들었고, 다시 돌리면 SHA256SUMS가 자기 자신을 담아 노트북 검증이 `SHA256SUMS: FAILED`를 낸다. 백업은 레포에 넣지 않는다.

## 5. 대상 지표 (읽기)

### 5-1 Mongo: 일치 문서

```bash
cat > "$B/q_targets.js" <<'TARGETS_JS'
const hex = id => (typeof id === "string" ? id : id.toHexString());
db.workflow_definitions.find({"nodes.event": "google_calendar.list"}, {_id: 1}).toArray()
  .forEach(d => print("DEL\t" + hex(d._id)));
TARGETS_JS
mongojs "$MDB_WF" "$B/q_targets.js" > "$B/targets_mongo.txt"
grep '^DEL' "$B/targets_mongo.txt" | cut -f2 > "$B/match_defs.txt"
grep -vE '^[0-9a-f]{24}$' "$B/match_defs.txt" && echo "형식 이상 — 멈춤"   # 아무것도 안 나와야 한다
wc -l < "$B/match_defs.txt"
```
`match_defs.txt`가 비었으면 삭제할 워크플로우가 없다 — `: > "$B/delete_defs.txt"`로 빈 파일을 만들고 §5-2·§6을 건너뛰어 §5-3 → §7로 간다.

### 5-2 PG: 대상 워크플로우·버전 문서·행 수

블록 넷째 줄(형식 검사) 뒤 `target_wf.txt`가 비었는데 `match_defs.txt`가 있으면(PG 버전이 없는 고아 Mongo 문서뿐) 나머지를 돌리지 말고 멈춰 사용자에게 고아 문서 처리를 묻는다 — 그대로 가면 `IN ()` 빈 목록으로 SQL 오류가 난다.

```bash
inlist() { sed "s/.*/'&'/" "$1" | paste -sd, -; }   # 24hex·uuid만 들어오므로 따옴표 감싸기로 충분하다
echo "SELECT DISTINCT workflow_id FROM workflow_versions WHERE mongo_definition_id IN ($(inlist "$B/match_defs.txt"));" \
  | psqlq -At > "$B/target_wf.txt"
grep -vE '^[0-9a-f-]{36}$' "$B/target_wf.txt" && echo "형식 이상 — 멈춤"
echo "SELECT mongo_definition_id FROM workflow_versions WHERE workflow_id IN ($(inlist "$B/target_wf.txt"));" \
  | psqlq -At > "$B/wf_defs.txt"
sort -u "$B/match_defs.txt" "$B/wf_defs.txt" > "$B/delete_defs.txt"   # 모든 버전 + PG에 매달리지 않은 일치 문서
wc -l "$B/target_wf.txt" "$B/delete_defs.txt"

{ echo "CREATE TEMP TABLE target_wf(id uuid PRIMARY KEY);"
  echo "INSERT INTO target_wf VALUES $(sed "s/.*/('&')/" "$B/target_wf.txt" | paste -sd, -);"
} > "$B/target_wf.sql"
cat > "$B/target_counts.sql" <<'COUNTS_SQL'
SELECT 'node_runs', count(*) FROM node_runs WHERE execution_id IN (SELECT id FROM workflow_runs WHERE workflow_id IN (SELECT id FROM target_wf))
UNION ALL SELECT 'workflow_runs', count(*) FROM workflow_runs WHERE workflow_id IN (SELECT id FROM target_wf)
UNION ALL SELECT 'node_test_samples', count(*) FROM node_test_samples WHERE workflow_id IN (SELECT id FROM target_wf)
UNION ALL SELECT 'app_trigger_subscriptions', count(*) FROM app_trigger_subscriptions WHERE workflow_id IN (SELECT id FROM target_wf)
UNION ALL SELECT 'workflow_webhook_tokens', count(*) FROM workflow_webhook_tokens WHERE workflow_id IN (SELECT id FROM target_wf)
UNION ALL SELECT 'chat_messages', count(*) FROM chat_messages WHERE session_id IN (SELECT id FROM chat_sessions WHERE workflow_id IN (SELECT id FROM target_wf))
UNION ALL SELECT 'chat_sessions', count(*) FROM chat_sessions WHERE workflow_id IN (SELECT id FROM target_wf)
UNION ALL SELECT 'workflow_versions', count(*) FROM workflow_versions WHERE workflow_id IN (SELECT id FROM target_wf)
UNION ALL SELECT 'workflows', count(*) FROM workflows WHERE id IN (SELECT id FROM target_wf);
SELECT 'active', count(*) FROM workflows WHERE is_active AND id IN (SELECT id FROM target_wf);
COUNTS_SQL
cat "$B/target_wf.sql" "$B/target_counts.sql" | psqlq -At | tee "$B/target_counts.txt"
```

### 5-3 Mongo: AI 교체 대상 (§6이 지우는 문서 전부를 뺀 수)

§6은 `delete_defs.txt`(일치 문서 + 대상 워크플로우의 모든 버전)를 지우므로, 교체 대상도 그 id 전체를 빼고 센다 — 일치 문서만 빼면 다른 버전에 있던 AI 도구가 섞여 §7 `matched`와 어긋난다.

```bash
{ printf 'const raw = ['; sed 's/.*/"&",/' "$B/delete_defs.txt" | tr -d '\n'; printf '];\n'
  echo 'const ids = raw.map(s => ObjectId(s)).concat(raw);'
  echo 'print("AI\t" + db.workflow_definitions.countDocuments({"nodes.config.tools.name": "builtin:google_calendar_list", _id: {$nin: ids}}));'
  echo 'print("AI_STR\t" + db.workflow_definitions.countDocuments({"nodes.config.tools": "builtin:google_calendar_list", _id: {$nin: ids}}));'
} > "$B/q_ai.js"
mongojs "$MDB_WF" "$B/q_ai.js" | tee "$B/targets_ai.txt"
```
`AI_STR`이 0이 아니면 멈추고 사용자에게 묻는다 — `config.tools`가 `["builtin:google_calendar_list"]` 같은 문자열 배열인 문서다(BE `ToolCallPreparer`·agent `get_tools_for_request`가 정식으로 받는 모양). §7은 `{name: …}` 객체 모양만 바꾸므로 이 문서는 교체에서 빠지고, 배포 뒤 agent가 모르는 이름으로 조용히 버린다.
⏸ **사용자 확인**: `target_counts.txt`(테이블별 지울 행 수, 활성 워크플로우 수), `delete_defs.txt` 줄 수, `AI`·`AI_STR` 수를 사용자에게 보이고 진행 여부를 받는다.

## 6. 삭제 (⏸ 사용자 확인 후, §4 백업 검증 뒤)

```bash
cat > "$B/delete_body.sql" <<'DELETE_SQL'
DELETE FROM node_runs WHERE execution_id IN (SELECT id FROM workflow_runs WHERE workflow_id IN (SELECT id FROM target_wf));
DELETE FROM workflow_runs WHERE workflow_id IN (SELECT id FROM target_wf);
DELETE FROM node_test_samples WHERE workflow_id IN (SELECT id FROM target_wf);
DELETE FROM app_trigger_subscriptions WHERE workflow_id IN (SELECT id FROM target_wf);
DELETE FROM workflow_webhook_tokens WHERE workflow_id IN (SELECT id FROM target_wf);
DELETE FROM chat_messages WHERE session_id IN (SELECT id FROM chat_sessions WHERE workflow_id IN (SELECT id FROM target_wf));
DELETE FROM chat_sessions WHERE workflow_id IN (SELECT id FROM target_wf);
DELETE FROM workflow_versions WHERE workflow_id IN (SELECT id FROM target_wf);
DELETE FROM workflows WHERE id IN (SELECT id FROM target_wf);
COMMIT;
DELETE_SQL
{ printf 'const raw = ['; sed 's/.*/"&",/' "$B/delete_defs.txt" | tr -d '\n'; printf '];\n'
  echo 'const ids = raw.map(s => ObjectId(s)).concat(raw);'
  echo 'print("workflow_definitions deleted\t" + db.workflow_definitions.deleteMany({_id: {$in: ids}}).deletedCount);'
} > "$B/delete_defs.js"

[ "$(hostname -s)" = kwungserver ] &&                                   # 노트북이면 멈춘다
[ -n "$MDB_WF" ] && [ -s "$B/target_wf.txt" ] &&
[ "$(for t in $TABLES_DEL; do grep -q '^COPY public\.' "$B/pg_$t.sql" 2>/dev/null && echo y; done | wc -l)" -eq 9 ] &&   # 9개 테이블 백업마다 COPY 블록. 리터럴 9라 TABLES_DEL이 비어도(0=0) 통과하지 않는다
[ -s "$B/mongo_workflow_definitions.jsonl" ] &&
{ echo 'BEGIN;'; cat "$B/target_wf.sql" "$B/delete_body.sql"; } | psqlq | tee "$B/delete_pg.txt" &&
mongojs "$MDB_WF" "$B/delete_defs.js" | tee "$B/delete_mongo.txt" &&
echo delete-done                  # 안 나오면 멈추고 어느 단계가 실패했는지 사용자에게 보인다
```
- `delete_pg.txt`의 `DELETE n`이 §5-2 `target_counts.txt`와 순서대로 같아야 한다(`ON_ERROR_STOP`이라 중간 실패면 COMMIT 전에 끊겨 PG는 그대로다).
- `workflow_definitions deleted` = `wc -l < "$B/delete_defs.txt"`(PG에만 있고 Mongo에 없는 id가 있으면 작을 수 있다 — 그때는 차이를 사용자에게 보인다).
- 확인:
  ```bash
  cat "$B/target_wf.sql" "$B/target_counts.sql" | psqlq -At          # 전부 0
  counts "$TABLES_DEL" > "$B/counts_after.txt"; paste "$B/counts_before.txt" "$B/counts_after.txt"   # 줄어든 양 = target_counts
  echo 'print("left\t" + db.workflow_definitions.countDocuments({"nodes.event": "google_calendar.list"}));' > "$B/q_left.js"
  mongojs "$MDB_WF" "$B/q_left.js"                                       # left 0
  ```

## 7. AI 도구 이름 교체 (⏸ 사용자 확인 후)

```bash
cat > "$B/rename.js" <<'RENAME_JS'
const OLD = "builtin:google_calendar_list", NEW = "builtin:google_calendar_find";
const r = db.workflow_definitions.updateMany(
  {"nodes.config.tools.name": OLD},
  {$set: {"nodes.$[n].config.tools.$[t].name": NEW}},
  {arrayFilters: [{"n.config.tools.name": OLD}, {"t.name": OLD}]});
print("matched\t" + r.matchedCount + "\tmodified\t" + r.modifiedCount);
print("remaining\t" + db.workflow_definitions.countDocuments({$or: [{"nodes.config.tools.name": OLD}, {"nodes.config.tools": OLD}]}));   // 문자열 배열 모양도 센다
RENAME_JS
[ "$(hostname -s)" = kwungserver ] && mongojs "$MDB_WF" "$B/rename.js" | tee "$B/rename.txt"
```
`matched` = `modified` = §5-3 `AI` 수, `remaining 0`. 같은 노드의 다른 도구·`config`는 그대로다(`$[t]`는 이름이 일치하는 원소만).

## 8. 정리

- e2e(§1-7)를 통과해 롤백 창이 닫힌 뒤, 노트북 사본 체크섬을 확인하고 `D=<백업 날짜>`로 고정한 셸에서 `rm -rf ~/ieum-dev-backup-cal-$D`(⏸ 사용자 확인).

## 9. 롤백 (agent·BE 새 배포 전, 아직 §3 정지 중일 때만)

새 코드가 뜬 뒤엔 `list` 도구가 없어 옛 데이터로 되돌릴 이유가 없다. 정지 중 처리를 되돌릴 때만(⏸ 사용자 확인). 여기는 **전체 사용자**의 9개 테이블과 `workflow_definitions` 전체를 비우고 복원하므로 가드를 건 한 체인으로만 돌린다. 새 셸이면 §2 블록을 `D=<백업 날짜>`로 고정해 다시 실행하고(`date +%F` 재계산은 자정을 넘기면 빈 폴더를 가리킨다) 전환 런북 §4-4로 `MDB_WF`를 다시 대입한다.
```bash
# PG — 백업 이후 바뀐 건 §6 삭제뿐이므로 9개를 비우고 통째 복원한다. 루프 종료 코드는 믿지 않고 개수 diff를 관문으로 쓴다
# Mongo — 삭제와 교체를 함께 되돌린다
[ "$(hostname -s)" = kwungserver ] &&                                   # 노트북이면 멈춘다
[ -n "$MDB_WF" ] &&
[ "$(for t in $TABLES_DEL; do grep -q '^COPY public\.' "$B/pg_$t.sql" 2>/dev/null && echo y; done | wc -l)" -eq 9 ] &&   # 리터럴 9 — 빈 $B·빈 TABLES_DEL이면 멈춘다
[ -s "$B/mongo_workflow_definitions.jsonl" ] && [ -s "$B/counts_before.txt" ] &&
echo "BEGIN; TRUNCATE TABLE node_runs, workflow_runs, node_test_samples, app_trigger_subscriptions, workflow_webhook_tokens, chat_messages, chat_sessions, workflow_versions, workflows; COMMIT;" | psqlq &&
for t in workflows workflow_versions workflow_runs node_runs node_test_samples app_trigger_subscriptions workflow_webhook_tokens chat_sessions chat_messages; do
  psqlq < "$B/pg_$t.sql"
done &&
counts "$TABLES_DEL" | diff "$B/counts_before.txt" - && echo pg-restored &&
echo 'print(db.workflow_definitions.deleteMany({}).deletedCount);' > "$B/wipe.js" &&
docker cp "$B/mongo_workflow_definitions.jsonl" ieum-mongo:/tmp/wd.jsonl &&
mongojs "$MDB_WF" "$B/wipe.js" &&
docker exec -e MDB="$MDB_WF" ieum-mongo sh -c 'mongoimport --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin -d "$MDB" -c workflow_definitions --file /tmp/wd.jsonl' &&
docker exec ieum-mongo rm -f /tmp/wd.jsonl &&
echo rollback-done                # 안 나오면 멈추고 어느 단계가 실패했는지 사용자에게 보인다
```
`pg-restored`가 나왔는데 `rollback-done`이 안 나오면 Mongo 단계에서 멈춘 것이다 — PG는 복원됐으니 Mongo 단계만 사용자에게 보이고 묻는다.
복원 뒤 `docker start ieum-backend ieum-agent`(옛 이미지 그대로)로 다시 띄운다.
