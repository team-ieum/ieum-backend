package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CONDITION 노드 Executor.
 *
 * <p>런타임이 넘기는 {@code input}(이미 치환된 config 사본)만 읽는다 — 참조식을 다시 치환하지 않는다.
 *
 * <ul>
 *   <li>새 모양: {@code branches}(갈래 최대 10개, 갈래마다 {@code key}·{@code name}·{@code match}(all|any)·
 *       {@code rules}(조건 최대 10개, {@code left}·{@code operator}·{@code right})), {@code pick}(first|all),
 *       {@code otherwise}(branch|stop), {@code caseSensitive}.</li>
 *   <li>레거시: {@code branches}가 없음·null·{@code ""}·빈 목록이면 {@code operator}·{@code left}(leftValue)·
 *       {@code right}(rightValue)를 갈래 하나(key {@code true}, 그 외 {@code false})로 읽는다.</li>
 * </ul>
 *
 * <p>출력: {@code branch}(첫 갈래 이름|null) {@code branches} {@code matched} {@code result}
 * {@code _branchKeys}({@link ExecutionCursor#BRANCH_KEYS} — 엔진이 이 key의 엣지만 실행한다).
 *
 * <p>설정 오류(모양·key·상한·모르는 operator·match·pick·otherwise)는 노드 실패다.
 * 값을 못 읽는 데이터 오류는 그 조건을 거짓으로 보고 경고만 모은다 — 노드는 성공한다.
 */
@Slf4j
@Component
public class ConditionNodeExecutor implements NodeExecutor {

    private static final int MAX_BRANCHES = 10;
    private static final int MAX_RULES = 10;
    private static final Set<String> RESERVED_KEYS = Set.of("else", "true", "false");
    private static final Set<String> OPERATORS = Set.of(
        "equals", "notEquals", "contains", "notContains", "startsWith", "endsWith", "in", "notIn",
        "greaterThan", "lessThan", "greaterThanOrEqual", "lessThanOrEqual", "numberEquals",
        "before", "after", "sameDay", "isTrue", "isFalse", "isEmpty", "isNotEmpty");
    private static final String OTHERWISE_NAME = "그 외";

    private record Rule(Object left, String operator, Object right) {
    }

    private record Branch(String key, String name, boolean any, List<Rule> rules) {
    }

    private record Setup(List<Branch> branches, boolean pickAll, boolean stop, boolean caseSensitive, boolean legacy) {
    }

    private record RuleResult(Rule rule, boolean passed) {
    }

    private record BranchResult(Branch branch, boolean matched, List<RuleResult> rules) {
    }

    private final Clock clock;

    public ConditionNodeExecutor() {
        this(Clock.system(ConditionValues.KST));
    }

    ConditionNodeExecutor(Clock clock) {
        this.clock = clock;
    }

    @Override
    public NodeType getNodeType() {
        return NodeType.CONDITION;
    }

    @Override
    public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
        long startTime = System.currentTimeMillis();
        try {
            Setup setup = parse(input);
            ZonedDateTime now = ZonedDateTime.now(clock);
            List<String> warnings = new ArrayList<>();
            List<BranchResult> results = new ArrayList<>();
            for (Branch branch : setup.branches()) {
                List<RuleResult> rules = new ArrayList<>();
                for (int i = 0; i < branch.rules().size(); i++) {
                    Rule rule = branch.rules().get(i);
                    String where = "갈래 '" + branch.name() + "' " + (i + 1) + "번째 조건";
                    rules.add(new RuleResult(rule, check(rule, setup.caseSensitive(), now, where, warnings)));
                }
                boolean matched = branch.any()
                    ? rules.stream().anyMatch(RuleResult::passed)
                    : rules.stream().allMatch(RuleResult::passed);
                results.add(new BranchResult(branch, matched, rules));
            }

            List<Branch> picked = results.stream().filter(BranchResult::matched).map(BranchResult::branch).toList();
            boolean matched = !picked.isEmpty();
            if (matched && !setup.pickAll()) {
                picked = picked.subList(0, 1);
            } else if (!matched && !setup.stop()) {
                picked = List.of(new Branch(setup.legacy() ? "false" : "else", OTHERWISE_NAME, false, List.of()));
            }

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("branch", picked.isEmpty() ? null : picked.get(0).name());
            output.put("branches", picked.stream().map(Branch::name).toList());
            output.put("matched", matched);
            output.put("result", matched);
            output.put(ExecutionCursor.BRANCH_KEYS, picked.stream().map(Branch::key).toList());
            log.info("[ConditionExecutor] 평가 완료 — nodeId: {}, branchKeys: {}, warnings: {}",
                node.getId(), output.get(ExecutionCursor.BRANCH_KEYS), warnings.size());
            return ExecutorResult.success(output, System.currentTimeMillis() - startTime);
        } catch (IllegalArgumentException e) {
            log.warn("[ConditionExecutor] 설정 오류 — nodeId: {}, {}", node.getId(), e.getMessage());
            return ExecutorResult.failure(e.getMessage(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("[ConditionExecutor] 실행 실패 — nodeId: {}", node.getId(), e);
            return ExecutorResult.failure(e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 설정 해석
    // ──────────────────────────────────────────────────────────────────────

    private static Setup parse(Map<String, Object> input) {
        Object caseFlag = input.get("caseSensitive");
        boolean caseSensitive = Boolean.TRUE.equals(caseFlag)
            || (caseFlag instanceof String s && s.equalsIgnoreCase("true"));
        Object raw = input.get("branches");

        if (raw == null || "".equals(raw) || (raw instanceof List<?> l && l.isEmpty())) {
            Object left = input.containsKey("left") ? input.get("left") : input.get("leftValue");
            Object right = input.containsKey("right") ? input.get("right") : input.get("rightValue");
            Rule rule = new Rule(left, operator(input.get("operator")), right);
            return new Setup(List.of(new Branch("true", "참", false, List.of(rule))), false, false, caseSensitive, true);
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("branches는 목록이어야 합니다.");
        }
        if (list.size() > MAX_BRANCHES) {
            throw new IllegalArgumentException("갈래는 최대 " + MAX_BRANCHES + "개입니다.");
        }
        List<Branch> branches = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("갈래는 객체여야 합니다.");
            }
            branches.add(branch(map, keys));
        }
        boolean pickAll = "all".equals(choice(input.get("pick"), "first", "pick", "first", "all"));
        boolean stop = "stop".equals(choice(input.get("otherwise"), "branch", "otherwise", "branch", "stop"));
        return new Setup(branches, pickAll, stop, caseSensitive, false);
    }

    private static Branch branch(Map<?, ?> map, Set<String> keys) {
        Object rawKey = map.get("key");
        if (rawKey != null && !(rawKey instanceof String)) {
            throw new IllegalArgumentException("갈래 key는 문자열이어야 합니다: " + rawKey);
        }
        String key = (String) rawKey;
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("갈래 key가 비어 있습니다.");
        }
        if (RESERVED_KEYS.contains(key)) {
            throw new IllegalArgumentException("갈래 key로 예약어를 쓸 수 없습니다: " + key);
        }
        if (!keys.add(key)) {
            throw new IllegalArgumentException("갈래 key가 중복됩니다: " + key);
        }
        String name = map.get("name") instanceof String s && !s.isEmpty() ? s : key;
        boolean any = "any".equals(choice(map.get("match"), "all", "match", "all", "any"));

        if (!(map.get("rules") instanceof List<?> rawRules) || rawRules.isEmpty()) {
            throw new IllegalArgumentException("갈래 '" + name + "'에 조건 목록이 없습니다.");
        }
        if (rawRules.size() > MAX_RULES) {
            throw new IllegalArgumentException("갈래 '" + name + "'의 조건은 최대 " + MAX_RULES + "개입니다.");
        }
        List<Rule> rules = new ArrayList<>();
        for (Object r : rawRules) {
            if (!(r instanceof Map<?, ?> rule)) {
                throw new IllegalArgumentException("갈래 '" + name + "'의 조건은 객체여야 합니다.");
            }
            rules.add(new Rule(rule.get("left"), operator(rule.get("operator")), rule.get("right")));
        }
        return new Branch(key, name, any, rules);
    }

    private static String operator(Object value) {
        if (!(value instanceof String op) || !OPERATORS.contains(op)) {
            throw new IllegalArgumentException("지원하지 않는 연산자: " + value);
        }
        return op;
    }

    /** null·{@code ""}면 기본값, 아니면 허용 값 중 하나여야 한다. */
    private static String choice(Object value, String fallback, String field, String... allowed) {
        if (value == null || "".equals(value)) {
            return fallback;
        }
        for (String a : allowed) {
            if (a.equals(value)) {
                return a;
            }
        }
        throw new IllegalArgumentException("지원하지 않는 " + field + " 값: " + value);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 조건 평가 — 판단 불가는 거짓 + 경고
    // ──────────────────────────────────────────────────────────────────────

    private static boolean check(Rule rule, boolean cs, ZonedDateTime now, String where, List<String> warnings) {
        Object l = rule.left();
        Object r = rule.right();
        String tr = ConditionValues.text(r, cs);
        return switch (rule.operator()) {
            case "equals" -> ConditionValues.text(l, cs).equals(tr);
            case "notEquals" -> !ConditionValues.text(l, cs).equals(tr);
            case "contains", "notContains", "startsWith", "endsWith" -> {
                if (tr.isEmpty()) {
                    warnings.add(where + ": 기준값이 비어 있어 거짓으로 봤어요.");
                    yield false;
                }
                String tl = ConditionValues.text(l, cs);
                yield switch (rule.operator()) {
                    case "startsWith" -> tl.startsWith(tr);
                    case "endsWith" -> tl.endsWith(tr);
                    default -> {
                        boolean has = ConditionValues.jsonList(l, cs).map(items -> items.contains(tr))
                            .orElseGet(() -> tl.contains(tr));
                        yield rule.operator().equals("contains") == has;
                    }
                };
            }
            case "in", "notIn" -> {
                List<String> items = ConditionValues.listItems(r, cs);
                if (items.isEmpty()) {
                    warnings.add(where + ": 기준값이 비어 있어 거짓으로 봤어요.");
                    yield false;
                }
                yield rule.operator().equals("in") == items.contains(ConditionValues.text(l, cs));
            }
            case "greaterThan" -> compareNumbers(l, r, where, warnings, c -> c > 0);
            case "lessThan" -> compareNumbers(l, r, where, warnings, c -> c < 0);
            case "greaterThanOrEqual" -> compareNumbers(l, r, where, warnings, c -> c >= 0);
            case "lessThanOrEqual" -> compareNumbers(l, r, where, warnings, c -> c <= 0);
            case "numberEquals" -> compareNumbers(l, r, where, warnings, c -> c == 0);
            case "before", "after", "sameDay" -> {
                Optional<ConditionValues.When> a = ConditionValues.date(l, now);
                Optional<ConditionValues.When> b = a.isEmpty() ? Optional.empty() : ConditionValues.date(r, now);
                if (a.isEmpty() || b.isEmpty()) {
                    warnings.add(where + ": '" + (a.isEmpty() ? l : r) + "'을(를) 날짜로 읽지 못해 거짓으로 봤어요.");
                    yield false;
                }
                yield switch (rule.operator()) {
                    case "before" -> ConditionValues.compare(a.get(), b.get()) < 0;
                    case "after" -> ConditionValues.compare(a.get(), b.get()) > 0;
                    default -> a.get().day().equals(b.get().day());
                };
            }
            case "isTrue" -> Boolean.TRUE.equals(ConditionValues.bool(l));
            case "isFalse" -> Boolean.FALSE.equals(ConditionValues.bool(l));
            case "isEmpty" -> ConditionValues.isEmpty(l);
            case "isNotEmpty" -> !ConditionValues.isEmpty(l);
            default -> throw new IllegalArgumentException("지원하지 않는 연산자: " + rule.operator());
        };
    }

    private static boolean compareNumbers(Object l, Object r, String where, List<String> warnings, IntPredicate test) {
        Optional<BigDecimal> a = ConditionValues.number(l);
        Optional<BigDecimal> b = a.isEmpty() ? Optional.empty() : ConditionValues.number(r);
        if (a.isEmpty() || b.isEmpty()) {
            warnings.add(where + ": '" + (a.isEmpty() ? l : r) + "'을(를) 숫자로 읽지 못해 거짓으로 봤어요.");
            return false;
        }
        return test.test(a.get().compareTo(b.get()));
    }
}
