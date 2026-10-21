package kr.mujoong.core.practice.start;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.practice.ScenarioRecordPort.Exposure;

/**
 * 기록 캡처의 {@code record.practice}에서 실제로 아이에게 보인 대본 노출을 읽는다. 두 형식을 읽는다.
 *
 * <ul>
 *   <li>U5b 형식({@code goals[].scenarios[].exposedAt}): 노출 시각이 찍힌 대본만 읽는다. 단서 시각은 노출을 대신하지 않는다.
 *   <li>새 연습 흐름 형식({@code flowVersion=practice-conversation-v1}, {@code scenes[]}): 장면을 시작한 대본을 노출로 본다. 같은 대본이 여러 장면
 *       (BASE · VARIANT)에 쓰이면 가장 이른 시작 시각 하나만 남긴다. {@code scenarioRef} · {@code startedAt}이 없는 장면은 건너뛴다.
 * </ul>
 *
 * <p>모르는 판 · 틀린 모양은 정산하지 않고 실패로 남긴다(정산 완료로 표시하지 않는다). 예외 메시지는 고정 문구만 쓴다.
 */
public final class RecordedScenarioExposures {

    /** 새 연습 흐름 기록 판. core 결과 투영({@code PracticeRecordV2.VERSION})과 같은 값이며 시험이 대조한다. */
    static final String FLOW_VERSION = "practice-conversation-v1";

    private RecordedScenarioExposures() {
    }

    public static List<Exposure> parse(String value, JsonCodec json) {
        if (value == null || "null".equals(value)) {
            return List.of();
        }
        Object raw = json.read(value, Object.class);
        if (!(raw instanceof Map<?, ?> practice)) {
            throw new IllegalArgumentException("연습 기록 형식 오류");
        }
        if (practice.containsKey("flowVersion")) {
            return flowScenes(practice);
        }
        return goalScenarios(practice);
    }

    private static List<Exposure> goalScenarios(Map<?, ?> practice) {
        if (!(practice.get("goals") instanceof List<?> goals)) {
            throw new IllegalArgumentException("연습 기록 형식 오류");
        }
        var exposures = new ArrayList<Exposure>();
        for (Object goal : goals) {
            if (!(goal instanceof Map<?, ?> g) || !(g.get("scenarios") instanceof List<?> scenarios)) {
                throw new IllegalArgumentException("장면 기록 형식 오류");
            }
            for (Object item : scenarios) {
                if (!(item instanceof Map<?, ?> scenario)) {
                    throw new IllegalArgumentException("장면 기록 형식 오류");
                }
                Object at = scenario.get("exposedAt");
                if (at == null) {
                    continue;
                }
                if (!(at instanceof String timestamp) || !(scenario.get("scenarioId") instanceof String id)) {
                    throw new IllegalArgumentException("장면 노출 형식 오류");
                }
                exposures.add(new Exposure(UUID.fromString(id), Instant.parse(timestamp)));
            }
        }
        return List.copyOf(exposures);
    }

    private static List<Exposure> flowScenes(Map<?, ?> practice) {
        if (!FLOW_VERSION.equals(practice.get("flowVersion"))) {
            throw new IllegalArgumentException("연습 기록 판 오류");
        }
        if (!(practice.get("scenes") instanceof List<?> scenes)) {
            throw new IllegalArgumentException("장면 기록 형식 오류");
        }
        Map<UUID, Instant> earliest = new LinkedHashMap<>();
        for (Object item : scenes) {
            if (!(item instanceof Map<?, ?> scene)) {
                throw new IllegalArgumentException("장면 기록 형식 오류");
            }
            Object ref = scene.get("scenarioRef");
            Object started = scene.get("startedAt");
            if (ref == null || started == null || (started instanceof String text && text.isBlank())) {
                continue;
            }
            earliest.merge(scenarioRef(ref), startedAt(started), (a, b) -> a.isAfter(b) ? b : a);
        }
        return earliest.entrySet().stream()
                .map(entry -> new Exposure(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(Exposure::exposedAt))
                .toList();
    }

    private static UUID scenarioRef(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("장면 노출 형식 오류");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("장면 노출 형식 오류", invalid);
        }
    }

    private static Instant startedAt(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("장면 노출 형식 오류");
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("장면 노출 형식 오류", invalid);
        }
    }
}
