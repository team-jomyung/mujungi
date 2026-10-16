package kr.mujoong.contract.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** U9 Luna 사건 로그 계약(SD6 · BR6.1 · BR6.2 · NFR1.2 · NFR3.5 · NFR4.1): 새 값 형식 LABEL · NAME_LIST와 두 사건의 허용 표. */
@Tag("u9")
class LunaEventLogTest {

    private static final Instant NOW = Instant.parse("2026-10-16T09:00:00Z");
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    /** 합성 응답 모델 이름(공급자가 돌려주는 판 붙은 이름 꼴). */
    private static final String RESPONSE_MODEL_NAME = "gpt-6-luna-2026.10";

    private final List<String> lines = new ArrayList<>();
    private final List<String> invalidKeys = new ArrayList<>();

    private EventLog log(EventLog.App app, EventLog.Strictness strictness) {
        MujoongClock clock = new MujoongClock() {
            @Override
            public Instant now() {
                return NOW;
            }

            @Override
            public long monotonicNanos() {
                return 0L;
            }
        };
        return new EventLog(app, clock, OwnerHasher.ephemeral(), () -> TRACE, (level, line) -> lines.add(level + " " + line),
                strictness, (event, key) -> invalidKeys.add(event + ":" + key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attrs(String line) {
        String json = line.substring(line.indexOf(' ') + 1);
        return (Map<String, Object>) JsonCodec.shared().read(json, LinkedHashMap.class).get("attrs");
    }

    @Test
    void lunaCallAcceptsExactlyTheSd6AttributesAndRendersThem() {
        log(EventLog.App.CORE_API, EventLog.Strictness.STRICT)
                .event(EventCode.LUNA_CALL, EventLog.Component.CORE_LUNA_CLIENT)
                .attr(AttrKey.LUNA_PURPOSE, "RESULT_TEXT")
                .attr(AttrKey.LUNA_OUTCOME, "OK")
                .attr(AttrKey.MODEL, "gpt-6-luna")
                .attr(AttrKey.RESPONSE_MODEL, RESPONSE_MODEL_NAME)
                .attr(AttrKey.PROMPT_VERSION, "v1")
                .attr(AttrKey.SENT_FIELDS, List.of("task", "turns", "goal_name"))
                .attr(AttrKey.LATENCY_MS, 812)
                .attr(AttrKey.ATTEMPT, 1)
                .emit();

        assertThat(EventCode.LUNA_CALL.allowedKeys().stream().map(AttrKey::key).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("lunaPurpose", "lunaOutcome", "model", "responseModel", "promptVersion",
                        "sentFields", "latencyMs", "attempt");
        assertThat(EventCode.LUNA_STARTUP.allowedKeys()).containsExactly(AttrKey.LUNA_STATE);
        assertThat(lines).singleElement().asString().startsWith("INFO ").contains("\"component\":\"core-api/LunaClient\"");
        assertThat(attrs(lines.getFirst())).containsOnly(
                Map.entry("lunaPurpose", "RESULT_TEXT"), Map.entry("lunaOutcome", "OK"), Map.entry("model", "gpt-6-luna"),
                Map.entry("responseModel", RESPONSE_MODEL_NAME), Map.entry("promptVersion", "v1"),
                Map.entry("sentFields", "task,turns,goal_name"), Map.entry("latencyMs", 812), Map.entry("attempt", 1));
        assertThat(EventCode.LUNA_CALL.isMergeable()).as("시도마다 한 줄(BR6.1)").isFalse();
    }

    @Test
    void labelValuesOutsideTheFormatBecomeUnknown() {
        EventLog strict = log(EventLog.App.CORE_API, EventLog.Strictness.STRICT);
        List<String> bad = List.of("-starts-with-dash", "has space", "모델", "a".repeat(65), "", "x\"y", "a/b");

        for (String value : bad) {
            String line = strict.event(EventCode.LUNA_CALL, EventLog.Component.CORE_LUNA_CLIENT)
                    .attr(AttrKey.MODEL, value).attr(AttrKey.PROMPT_VERSION, value).render();
            assertThat(attrs("X " + line)).as(value).containsEntry("model", "UNKNOWN").containsEntry("promptVersion", "UNKNOWN");
        }
        assertThat(StructuredLogFormatter.isValidLabel("gpt-6-luna")).isTrue();
        assertThat(StructuredLogFormatter.isValidLabel("result.v1_2")).isTrue();
        assertThat(StructuredLogFormatter.isValidLabel("a".repeat(64))).isTrue();
        assertThat(StructuredLogFormatter.isValidLabel(null)).isFalse();
    }

    @Test
    void nameListValuesOutsideTheFormatBecomeUnknown() {
        EventLog strict = log(EventLog.App.REALTIME, EventLog.Strictness.STRICT);
        List<String> thirtyThree = IntStream.rangeClosed(1, 33).mapToObj(i -> "f" + i).toList();
        List<String> bad = List.of("-dash", "has space", "이름", "a,,b", "a,", ",a", "1abc", String.join(",", thirtyThree),
                "a-b");

        for (String value : bad) {
            String line = strict.event(EventCode.LUNA_CALL, EventLog.Component.REALTIME_LUNA_CLIENT)
                    .attr(AttrKey.SENT_FIELDS, value).render();
            assertThat(attrs("X " + line)).as(value).containsEntry("sentFields", "UNKNOWN");
        }
        String withNull = strict.event(EventCode.LUNA_CALL, EventLog.Component.REALTIME_LUNA_CLIENT)
                .attr(AttrKey.SENT_FIELDS, Arrays.asList("a", null)).render();
        assertThat(attrs("X " + withNull)).containsEntry("sentFields", "UNKNOWN");
        List<String> thirtyTwo = thirtyThree.subList(0, 32);
        assertThat(StructuredLogFormatter.isValidNameList(String.join(",", thirtyTwo))).isTrue();
        assertThat(StructuredLogFormatter.isValidNameList("")).as("빈 목록은 0개").isTrue();
        assertThat(StructuredLogFormatter.isValidNameList(null)).isFalse();
    }

    @Test
    void lunaCallRejectsKeysOutsideItsTableAndWrongValueTypes() {
        EventLog strict = log(EventLog.App.CORE_API, EventLog.Strictness.STRICT);

        assertThatThrownBy(() -> strict.event(EventCode.LUNA_CALL, EventLog.Component.CORE_LUNA_CLIENT)
                        .attr(AttrKey.STATUS_CODE, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statusCode");
        assertThatThrownBy(() -> strict.event(EventCode.LUNA_CALL, EventLog.Component.CORE_LUNA_CLIENT)
                        .attr(AttrKey.LATENCY_MS, "812"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strict.event(EventCode.LUNA_STARTUP, EventLog.Component.CORE_LUNA_CLIENT)
                        .attr(AttrKey.MODEL, "gpt-6-luna"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
        assertThatThrownBy(() -> strict.event(EventCode.LUNA_CALL, EventLog.Component.REALTIME_LUNA_CLIENT))
                .as("다른 앱의 component")
                .isInstanceOf(IllegalArgumentException.class);

        log(EventLog.App.CORE_API, EventLog.Strictness.LENIENT)
                .event(EventCode.LUNA_CALL, EventLog.Component.CORE_LUNA_CLIENT)
                .attr(AttrKey.LUNA_OUTCOME, "OK")
                .attr(AttrKey.TARGET, "SESSION_START")
                .emit();
        assertThat(invalidKeys).containsExactly("LUNA_CALL:target");
        assertThat(attrs(lines.getFirst())).containsOnlyKeys("lunaOutcome");
    }

    @Test
    void unregisteredPurposeOutcomeAndStateBecomeUnknownAndFailuresAreWarn() {
        EventLog strict = log(EventLog.App.REALTIME, EventLog.Strictness.STRICT);

        String line = strict.event(EventCode.LUNA_CALL, EventLog.Component.REALTIME_LUNA_CLIENT)
                .attr(AttrKey.LUNA_PURPOSE, "CHAT").attr(AttrKey.LUNA_OUTCOME, "MAYBE").render();
        String startup = strict.event(EventCode.LUNA_STARTUP, EventLog.Component.REALTIME_LUNA_CLIENT)
                .attr(AttrKey.LUNA_STATE, "HALF_ON").render();

        assertThat(attrs("X " + line)).containsEntry("lunaPurpose", "UNKNOWN").containsEntry("lunaOutcome", "UNKNOWN");
        assertThat(attrs("X " + startup)).containsEntry("lunaState", "UNKNOWN");
        assertThat(strict.event(EventCode.LUNA_CALL, EventLog.Component.REALTIME_LUNA_CLIENT)
                .attr(AttrKey.LUNA_OUTCOME, "TIMEOUT").effectiveLevel()).isEqualTo(EventCode.Level.WARN);
        assertThat(strict.event(EventCode.LUNA_CALL, EventLog.Component.REALTIME_LUNA_CLIENT)
                .attr(AttrKey.LUNA_OUTCOME, "OK").effectiveLevel()).isEqualTo(EventCode.Level.INFO);
        assertThat(strict.event(EventCode.LUNA_STARTUP, EventLog.Component.REALTIME_LUNA_CLIENT)
                .attr(AttrKey.LUNA_STATE, "DISABLED_NO_KEY").effectiveLevel()).isEqualTo(EventCode.Level.INFO);
        assertThat(AttrKey.LUNA_OUTCOME.enumValues()).hasSize(13).contains("OK", "FORBIDDEN_INPUT", "MODEL_MISSING");
        assertThat(EventLog.Component.CORE_LUNA_CLIENT.value()).isEqualTo("core-api/LunaClient");
        assertThat(EventLog.Component.REALTIME_LUNA_CLIENT.value()).isEqualTo("realtime/LunaClient");
    }
}
