package kr.mujoong.realtime.judgment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 판정 요청 한 건 (U11 entities JudgeRequest · BR1.2 · BR1.7). {@code contracts/schemas/ai-judgment-request.schema.json} 모양에 맞는 필드만
 * 담는다: 지점, 판 번호, 판 순서 그대로의 선택지, 확정 전사, 최근 대화 최대 3턴, J2 · J3 · J5만 기준이다. 아동 · 보호자 · 세션 · 기기 식별자와
 * 원음, 보호자 입력 원문 필드는 아예 없다(R-F14 · R-F15). 별칭 표(아이 이름 → 아이)는 Luna 입력 정리가 치환에만 쓰고 공급자로 가지 않는다.
 * 같은 걸음 묶음 · 낮춘 하한도 검증기만 읽고 공급자로 가지 않는다({@link #toLunaInput()}에 없다). 문자열 표현은 본문을 숨긴다.
 *
 * @param childUtterance 판정 대상 확정 전사. 비었을 수 있다(그러면 공급자 없이 UNREADABLE_INPUT, BR4.3). 2000자 이하는 호출자가 지킨다
 * @param uncertain 전사 신뢰도가 낮다고 표시됐는가. 입력 신뢰도를 알 수 없으면 거짓이다(BR4.3, 설계 개정 A7)
 * @param deadline 호출자 마감({@code MujoongClock} 기준). 보통 판정 턴 열린 시각 + live-budget이다(BR1.4)
 * @param sameStepGroups 판정을 요청한 연습 흐름이 지금 단계에서 같은 다음 걸음으로 처리하는 선택지 묶음(없으면 빈 목록). 묶음마다 서로 다른 요청
 *     선택지 둘 이상이고, 한 선택지는 한 묶음에만 든다. 1 · 2순위가 같은 묶음이면 검증기가 하한을 두 확률의 합으로 본다(ai-io.md 10.10)
 * @param minConfidence 판정을 요청한 쪽이 이 판정에만 쓰자고 실은 낮춘 확률 하한(없으면 null = 전역 하한, C안 기회 J2 — 편차 FT-1024-h). 0 이상 1
 *     이하다(실행부가 받기 전에 허용 범위를 따로 본다). 검증기는 전역 하한보다 낮을 때만 쓴다(낮추기만 한다)
 */
public record JudgeRequest(
        JudgmentPoint point,
        String optionSetVersion,
        List<String> options,
        String childUtterance,
        List<RecentTurn> recentTurns,
        Criteria criteria,
        Map<String, String> aliases,
        boolean uncertain,
        Instant deadline,
        List<List<String>> sameStepGroups,
        Double minConfidence) {

    /** 요청 전사 · 대화 한 줄의 상한(스키마 maxLength). */
    public static final int MAX_TEXT_CHARS = 2000;
    /** 최근 대화 상한(BR1.7). */
    public static final int MAX_RECENT_TURNS = 3;

    /** 화자 표시(스키마 enum). MUJOONG은 장면 속 역할 없이 무중이 자기 말로 한 줄이다(C안, docs/practice-direct-ask.md 6장). */
    public enum Speaker {
        CHILD,
        PARTNER,
        COACH,
        MUJOONG
    }

    /** 최근 대화 한 줄. */
    public record RecentTurn(Speaker speaker, String text) {
        public RecentTurn {
            Objects.requireNonNull(speaker, "speaker");
            if (text == null || text.isBlank() || text.length() > MAX_TEXT_CHARS) {
                throw new IllegalArgumentException("최근 대화 한 줄은 1 ~ 2000자다");
            }
        }

        @Override
        public String toString() {
            return "RecentTurn[redacted]";
        }
    }

    /** 담은 목표 4요소에서 만든 판정 기준(J2 · J3 · J5). */
    public record Criteria(String coreFunction, String judgmentCriteria) {
        public Criteria {
            if (coreFunction == null || coreFunction.isBlank() || coreFunction.length() > 500) {
                throw new IllegalArgumentException("coreFunction은 1 ~ 500자다");
            }
            if (judgmentCriteria == null || judgmentCriteria.isBlank() || judgmentCriteria.length() > MAX_TEXT_CHARS) {
                throw new IllegalArgumentException("judgmentCriteria는 1 ~ 2000자다");
            }
        }

        @Override
        public String toString() {
            return "Criteria[redacted]";
        }
    }

    public JudgeRequest {
        Objects.requireNonNull(point, "point");
        if (optionSetVersion == null || optionSetVersion.isBlank() || optionSetVersion.length() > 80) {
            throw new IllegalArgumentException("optionSetVersion은 1 ~ 80자다");
        }
        Objects.requireNonNull(options, "options");
        options = List.copyOf(options);
        if (options.isEmpty() || new LinkedHashSet<>(options).size() != options.size()) {
            throw new IllegalArgumentException("선택지는 비지 않고 중복이 없어야 한다");
        }
        Objects.requireNonNull(childUtterance, "childUtterance");
        if (childUtterance.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("확정 전사는 2000자 이하여야 한다");
        }
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
        if (recentTurns.size() > MAX_RECENT_TURNS) {
            throw new IllegalArgumentException("최근 대화는 최대 3턴이다");
        }
        if (criteria != null && !(point == JudgmentPoint.J2 || point == JudgmentPoint.J3 || point == JudgmentPoint.J5)) {
            throw new IllegalArgumentException("판정 기준은 J2 · J3 · J5만 쓴다");
        }
        aliases = aliases == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(aliases));
        Objects.requireNonNull(deadline, "deadline");
        sameStepGroups = sameStepGroups == null ? List.of() : copyGroups(sameStepGroups, options);
        if (minConfidence != null && (minConfidence.isNaN() || minConfidence < 0.0 || minConfidence > 1.0)) {
            throw new IllegalArgumentException("낮춘 하한은 0 이상 1 이하다");
        }
    }

    /** 같은 걸음 묶음이 없는 요청(묶음을 싣지 않는 호출처 · 시험이 그대로 쓴다). */
    public JudgeRequest(JudgmentPoint point, String optionSetVersion, List<String> options, String childUtterance,
            List<RecentTurn> recentTurns, Criteria criteria, Map<String, String> aliases, boolean uncertain, Instant deadline) {
        this(point, optionSetVersion, options, childUtterance, recentTurns, criteria, aliases, uncertain, deadline, List.of());
    }

    /** 낮춘 하한이 없는 요청(전역 하한 그대로, 하한을 싣지 않는 호출처 · 시험이 그대로 쓴다). */
    public JudgeRequest(JudgmentPoint point, String optionSetVersion, List<String> options, String childUtterance,
            List<RecentTurn> recentTurns, Criteria criteria, Map<String, String> aliases, boolean uncertain, Instant deadline,
            List<List<String>> sameStepGroups) {
        this(point, optionSetVersion, options, childUtterance, recentTurns, criteria, aliases, uncertain, deadline, sameStepGroups, null);
    }

    /** 묶음의 바뀌지 않는 사본. 둘 미만 · 요청 선택지 밖 · 묶음 안 중복 · 묶음끼리 겹침은 예외다. */
    private static List<List<String>> copyGroups(List<List<String>> groups, List<String> options) {
        Set<String> seen = new HashSet<>();
        List<List<String>> copies = new ArrayList<>(groups.size());
        for (List<String> group : groups) {
            List<String> copy = List.copyOf(Objects.requireNonNull(group, "group"));
            if (copy.size() < 2) {
                throw new IllegalArgumentException("같은 걸음 묶음은 선택지 둘 이상이다");
            }
            for (String code : copy) {
                if (!options.contains(code) || !seen.add(code)) {
                    throw new IllegalArgumentException("같은 걸음 묶음은 요청 선택지 안의 코드를 한 번씩만 담는다");
                }
            }
            copies.add(copy);
        }
        return List.copyOf(copies);
    }

    /** 스키마 모양의 Luna 입력(BR2.4가 요청 스키마로 다시 검사한다). */
    public Map<String, Object> toLunaInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("judgmentPoint", point.name());
        input.put("optionSetVersion", optionSetVersion);
        input.put("options", options);
        input.put("childUtterance", childUtterance);
        if (!recentTurns.isEmpty()) {
            input.put("recentTurns", recentTurns.stream()
                    .map(turn -> Map.<String, Object>of("speaker", turn.speaker().name(), "text", turn.text())).toList());
        }
        Map<String, Object> criteriaNode = null;
        if (criteria != null) {
            criteriaNode = new LinkedHashMap<>();
            criteriaNode.put("coreFunction", criteria.coreFunction());
            criteriaNode.put("judgmentCriteria", criteria.judgmentCriteria());
        }
        input.put("criteria", criteriaNode);
        return input;
    }

    @Override
    public String toString() {
        return "JudgeRequest[point=" + point + ", redacted]";
    }
}
