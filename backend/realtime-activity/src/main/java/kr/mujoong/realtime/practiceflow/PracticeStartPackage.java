package kr.mujoong.realtime.practiceflow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.practiceflow.PracticeScriptException.Reason;
import kr.mujoong.realtime.practiceflow.PracticeScriptReader.RawScript;

/**
 * 시작 패키지의 {@code practice} 조각을 읽은 값 (U12 FR1.2 · FR1.3 · BR1.3 · BR1.4). 세션당 하나이고 읽기 전용이다. realtime이 원문 JSON을
 * 보존하고(BR1.2) 이 값은 그것을 해석한다.
 *
 * <ul>
 *   <li>목표: 패키지의 {@code goals[]}를 {@code order} 순으로 읽어 {@link GoalSpec}으로 만든다. 목표가 없거나 목표 필수 값(goalSelectionId ·
 *       displayName · judgmentCriteria)이 없으면 {@link Reason#BAD_PACKAGE}다. goalSelectionId는 목표마다 있다(기록의 goalRef).
 *   <li>첫 대본: 첫 목표(order 1)에만 {@code firstScenario}가 있다. {@code scriptVersion}이 {@value PracticeScript#SCRIPT_VERSION_V1} 또는
 *       {@value PracticeScript#SCRIPT_VERSION_V2}이면 새 흐름 대상이고, 없으면 레거시다(BR1.4). 새 흐름인데 대본이 계약을 어기면
 *       {@link #firstScenario}는 null이고 {@link #firstScenarioRejection}에 사유가 남는다(그 목표는 '미실시', BR1.3).
 * </ul>
 *
 * @param goals 목표 목록(1건 이상, order 순)
 * @param firstScenario 읽은 첫 목표의 대본(없거나 새 흐름 아니거나 거절되면 null)
 * @param scriptVersion 첫 대본의 scriptVersion 원문(없으면 null)
 * @param firstScenarioRejection 새 흐름 판인데 거절된 첫 대본의 사유(아니면 null)
 */
public record PracticeStartPackage(List<GoalSpec> goals, PracticeScript firstScenario, String scriptVersion,
        Reason firstScenarioRejection) {

    public PracticeStartPackage {
        goals = List.copyOf(Objects.requireNonNull(goals, "goals"));
        if (goals.isEmpty()) {
            throw new IllegalArgumentException("목표가 없다");
        }
    }

    // ---- 읽기 전용 JSON 모양 ----

    /** 목표 JSON 모양. */
    record RawGoal(String goalSelectionId, Integer order, String displayName, String judgmentCriteria, RawScript firstScenario) {
    }

    /** practice 조각 JSON 모양. */
    record RawPackage(List<RawGoal> goals) {
    }

    /**
     * practice 조각 JSON 원문을 읽는다.
     *
     * @throws PracticeScriptException {@link Reason#BAD_PACKAGE}(원문이 없거나 모양이 틀림, 목표 없음, 목표 필수 값 없음)
     */
    public static PracticeStartPackage parse(String practiceJson) {
        if (practiceJson == null || practiceJson.isBlank()) {
            throw new PracticeScriptException(Reason.BAD_PACKAGE);
        }
        RawPackage raw;
        try {
            raw = JsonCodec.shared().read(practiceJson, RawPackage.class);
        } catch (RuntimeException e) {
            throw new PracticeScriptException(Reason.BAD_PACKAGE, e);
        }
        if (raw == null || raw.goals() == null || raw.goals().isEmpty()) {
            throw new PracticeScriptException(Reason.BAD_PACKAGE);
        }
        List<RawGoal> ordered = new ArrayList<>(raw.goals());
        if (ordered.stream().anyMatch(Objects::isNull)) {
            throw new PracticeScriptException(Reason.BAD_PACKAGE);
        }
        // order가 없으면 뒤로 보내고(안정 정렬) 있으면 오름차순
        ordered.sort(Comparator.comparing(RawGoal::order, Comparator.nullsLast(Comparator.naturalOrder())));
        List<GoalSpec> goals = new ArrayList<>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            RawGoal goal = ordered.get(index);
            UUID id = PracticeScriptReader.uuid(goal.goalSelectionId());
            if (id == null || blank(goal.displayName()) || blank(goal.judgmentCriteria())) {
                throw new PracticeScriptException(Reason.BAD_PACKAGE);
            }
            goals.add(new GoalSpec(id, index, goal.displayName(), goal.judgmentCriteria()));
        }
        RawScript first = ordered.getFirst().firstScenario();
        String version = first == null ? null : first.scriptVersion();
        boolean newFlow = PracticeScript.readable(version);
        PracticeScript script = null;
        Reason rejection = null;
        if (newFlow) {
            try {
                script = PracticeScriptReader.read(first);
            } catch (PracticeScriptException e) {
                rejection = e.reason();
            }
        }
        return new PracticeStartPackage(goals, script, version, rejection);
    }

    /** 새 흐름 대상인가(첫 대본의 scriptVersion이 새 흐름이 읽는 판 v1 · v2, BR1.4). 없으면 레거시다. */
    public boolean usesNewFlow() {
        return PracticeScript.readable(scriptVersion);
    }

    /** 첫 목표. */
    public GoalSpec firstGoal() {
        return goals.getFirst();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 판정 기준 · 대본 문장이 담겨 문자열 표현에 내지 않는다. */
    @Override
    public String toString() {
        return "PracticeStartPackage[goals=" + goals.size() + ", usesNewFlow=" + usesNewFlow() + "]";
    }
}
