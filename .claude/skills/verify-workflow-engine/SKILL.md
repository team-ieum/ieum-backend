---
name: verify-workflow-engine
description: 워크플로우 실행 엔진 패턴 규칙을 검증합니다. NodeExecutor 구현체 등록, @ConditionalOnMissingBean Stub 패턴, ExecutorResult 생성 방식, node_runs JSONB 자격증명 원문 저장 금지를 확인합니다.
type: verify
---

# verify-workflow-engine

## 목적

워크플로우 엔진 계층(`engine/`, `engine/executor/`, `executor/`)이 프로젝트 규칙에 맞게 구현되었는지 검증합니다.

## 실행 시점

- 새로운 `NodeExecutor` 구현체를 추가했을 때
- `SyncExecutionRuntime` 또는 `ExecutionCursor`를 수정했을 때
- Stub 구현체를 실제 구현체로 교체했을 때
- PR 전 최종 검증 시

## Related Files

| File | Purpose |
|------|---------|
| `workflow-core/src/main/java/com/ieum/workflowcore/engine/executor/NodeExecutor.java` | NodeExecutor 인터페이스 |
| `workflow-core/src/main/java/com/ieum/workflowcore/engine/SyncExecutionRuntime.java` | 노드 순회 실행 런타임 |
| `workflow-core/src/main/java/com/ieum/workflowcore/engine/ExecutorResult.java` | 노드 실행 결과 |
| `workflow-core/src/main/java/com/ieum/workflowcore/engine/ExecutionCursor.java` | 노드 그래프 순회 커서 |
| `workflow-core/src/main/java/com/ieum/workflowcore/engine/executor/TriggerNodeExecutor.java` | TRIGGER 노드 실행자 |
| `workflow-core/src/main/java/com/ieum/workflowcore/engine/executor/ConditionNodeExecutor.java` | CONDITION 노드 실행자 |
| `workflow-core/src/main/java/com/ieum/workflowcore/engine/executor/TransformNodeExecutor.java` | TRANSFORM 노드 실행자 |
| `workflow-core/src/main/java/com/ieum/workflowcore/engine/executor/HttpNodeExecutor.java` | HTTP 노드 실행자 |
| `workflow-core/src/main/java/com/ieum/workflowcore/executor/AgentNodeExecutor.java` | AI(Agent) 노드 실행자 |
| `workflow-core/src/main/java/com/ieum/workflowcore/engine/executor/CredentialProvider.java` | 자격증명 복호화 포트 인터페이스 |
| `workflow-core/src/main/java/com/ieum/workflowcore/engine/executor/StubCredentialProvider.java` | 임시 Stub 구현체 |
| `workflow-core/src/main/java/com/ieum/workflowcore/config/WorkflowConfig.java` | @EnableAsync + ThreadPoolTaskExecutor |
| `api/src/main/java/com/ieum/api/workflow/WorkflowExecutionRunner.java` | @Async 비동기 실행 컴포넌트 |

## Workflow

### Check 1: NodeExecutor 구현체에 @Component 등록 확인

`NodeExecutor`를 구현한 클래스는 반드시 `@Component`가 있어야 합니다.
없으면 `SyncExecutionRuntime`의 `List<NodeExecutor>` 주입에서 제외되어 `NodeExecutor 없음` 런타임 에러가 납니다.

```bash
# NodeExecutor를 implements하는 파일 목록
grep -rln "implements NodeExecutor" --include="*.java" . | grep -v test | grep -v build

# 그 중 @Component가 없는 파일 탐지
grep -rln "implements NodeExecutor" --include="*.java" . | grep -v test | grep -v build | \
  xargs grep -L "@Component"
```

**PASS:** 결과가 없음 (모든 구현체에 @Component 존재)
**FAIL:** 1건 이상 — `@Component` 추가 필요

### Check 2: NodeExecutor 구현체에 getNodeType() 구현 확인

`getNodeType()`은 executor 맵 키로 사용되므로 반드시 구현해야 합니다.

```bash
# NodeExecutor 구현체 파일 중 getNodeType()이 없는 경우 탐지
grep -rln "implements NodeExecutor" --include="*.java" . | grep -v test | grep -v build | \
  xargs grep -L "getNodeType"
```

**PASS:** 결과가 없음
**FAIL:** 1건 이상 — `getNodeType()` 메서드 구현 필요

### Check 3: StubCredentialProvider @ConditionalOnMissingBean 패턴 확인

Stub 구현체는 실제 구현체로 자동 대체될 수 있도록 `@ConditionalOnMissingBean`이 있어야 합니다.

```bash
grep -rn "@ConditionalOnMissingBean" --include="*.java" . | grep -v test | grep -v build
grep -rn "StubCredentialProvider" --include="*.java" . | grep -v test | grep -v build
```

**PASS:** `StubCredentialProvider`에 `@ConditionalOnMissingBean` 존재
**FAIL:** 미존재 — Stub과 실제 구현체가 동시에 등록되어 빈 충돌 발생

### Check 4: ExecutorResult 정적 팩토리 사용 확인

`ExecutorResult`는 `new ExecutorResult()`로 직접 생성하지 않고, 반드시 `ExecutorResult.success()` 또는 `ExecutorResult.failure()`를 사용해야 합니다.

```bash
grep -rn "new ExecutorResult" --include="*.java" . | grep -v test | grep -v build
```

**PASS:** 결과가 없음 (0건)
**FAIL:** 1건 이상 — `ExecutorResult.success(output, durationMs)` 또는 `ExecutorResult.failure(message, durationMs)`로 교체

### Check 5: node_runs JSONB 자격증명 원문 저장 금지

`inputJson` / `outputJson`에 자격증명 원문(`apiKey`, `token`, `secret`, `password`)이 포함되어선 안 됩니다.
config에는 `credentialId`(참조)만 저장하고, 실행 시 서버 내부에서만 복호화합니다.

```bash
# WorkflowExecutionLog 빌더 사용 위치 확인
grep -rn "WorkflowExecutionLog" --include="*.java" . | grep -v test | grep -v build | grep "builder\|build()"

# node_runs 저장 시 자격증명 키워드가 inputJson/outputJson에 직접 포함되는지 확인
grep -rn "inputJson\|outputJson" --include="*.java" . | grep -v test | grep -v build | \
  grep -i "apiKey\|api_key\|token\|secret\|password"
```

**PASS:** 결과가 없음 — inputJson/outputJson에 자격증명 원문 없음
**FAIL:** 1건 이상 — 저장 전 자격증명 필드 필터링 필요

### Check 6: WorkflowExecutionRunner @Async 어노테이션 확인

`WorkflowExecutionRunner.run()`은 반드시 `@Async("workflowExecutor")`가 있어야 합니다.
없으면 동기 실행되어 HTTP 응답이 블로킹됩니다.

```bash
grep -rn "@Async" --include="WorkflowExecutionRunner.java" . | grep -v test | grep -v build
```

**PASS:** `@Async("workflowExecutor")` 존재
**FAIL:** 미존재 — `@Async("workflowExecutor")` 추가 및 `WorkflowConfig`에 `workflowExecutor` 빈 등록 확인

## 예외사항

다음은 **위반이 아닙니다**:

1. **테스트 코드** — `src/test` 하위 파일은 검사 제외
2. **빌드 생성 파일** — `build/` 하위 파일은 검사 제외
3. **NodeExecutor 인터페이스 자체** — `NodeExecutor.java`는 구현체가 아니므로 Check 1, 2 제외
4. **StubCredentialProvider** — Check 1 대상이지만, `@ConditionalOnMissingBean`이 있으므로 실제 구현체 등록 시 자동 제외됨
5. **SyncExecutionRuntime의 IllegalStateException** — TRIGGER 노드 없음, Executor 미등록 등 시스템 불변식 위반은 의도된 예외이며 Check 2(CustomException 규칙) 대상 아님
