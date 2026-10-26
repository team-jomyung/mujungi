package kr.mujoong.realtime.practiceflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeScript.Scene;
import kr.mujoong.realtime.practiceflow.PracticeVoiceChild.GoalLines;
import kr.mujoong.realtime.practiceflow.PracticeVoiceFacts.Attempt;
import kr.mujoong.realtime.practiceflow.PracticeVoiceFacts.ChildTurn;
import kr.mujoong.realtime.practiceflow.PracticeVoiceFacts.ClockEvent;
import kr.mujoong.realtime.practiceflow.PracticeVoiceFacts.Discard;
import kr.mujoong.realtime.practiceflow.PracticeVoiceFacts.Flow;
import kr.mujoong.realtime.practiceflow.PracticeVoiceFacts.GuardCheck;
import kr.mujoong.realtime.practiceflow.PracticeVoiceFacts.Heard;
import kr.mujoong.realtime.practiceflow.PracticeVoiceFacts.JudgeCall;
import kr.mujoong.realtime.practiceflow.PracticeVoiceFacts.Play;
import kr.mujoong.realtime.practiceflow.PracticeVoiceFacts.Reading;
import kr.mujoong.realtime.practiceflow.PracticeVoiceFacts.Row;
import kr.mujoong.realtime.practiceflow.PracticeVoiceFacts.SceneRow;
import kr.mujoong.realtime.practiceflow.PracticeVoiceFacts.Snapshot;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실음성 연습 하니스의 기록({@link PracticeVoiceDialogueLiveTest}). 대화 하나의 사실({@link Snapshot})을 '아이가 실제로 들은 말' 차례로 엮어
 * JSON 한 줄과 Markdown 한 절로 만들고, 여러 대화를 요약 표(장면 결과 · 승인 원문 읽기 · 무중 차례 대기 · 전하는 식 대답의 J2 · 무응답 시계 ·
 * GPT-Live 자동 응답)로 묶는다. 값은 하니스가 모은 합성 대화의 관측이고 운영 판단 기준이 아니다(R-M11). 대기 '느림 · 긴 대기' 경계는 하니스 참고
 * 값이다.
 */
final class PracticeVoiceReport {

    /** 무중 차례 대기가 이보다 길면 '느림'으로 센다(하니스 참고 값, 운영 기준 아님). */
    static final long WAIT_SLOW_MS = 3_000L;
    /** 무중 차례 대기가 이보다 길면 '긴 대기'로 센다(하니스 참고 값, 옛 하니스와 같은 6초). */
    static final long WAIT_LONG_MS = 6_000L;
    /** 아이 말 시작보다 이만큼 앞서 확정된 전사 행도 그 차례로 본다(가상 기기 시각과 실행부 시각의 차). */
    private static final long ROW_LEAD_MS = 300L;

    static final List<String> READING_OUTCOMES = List.of(PracticeVoiceFacts.FIRST_MATCH, PracticeVoiceFacts.REREAD_MATCH,
            PracticeVoiceFacts.RETRY_MATCH, PracticeVoiceFacts.RECOVERED, PracticeVoiceFacts.PARTIAL, PracticeVoiceFacts.FAILED,
            PracticeVoiceFacts.INCOMPLETE);
    static final Map<String, String> READING_LABELS = Map.of(PracticeVoiceFacts.FIRST_MATCH, "첫 시도 일치", PracticeVoiceFacts.REREAD_MATCH,
            "나머지 다시 읽기로 다 들림", PracticeVoiceFacts.RETRY_MATCH, "같은 글 재시도 일치", PracticeVoiceFacts.RECOVERED, "'뜻 전하기' 복구",
            PracticeVoiceFacts.PARTIAL, "앞부분만 들림", PracticeVoiceFacts.FAILED, "실패", PracticeVoiceFacts.INCOMPLETE, "미완(끝남 · 취소)");
    private static final Map<String, String> END_LABELS = Map.ofEntries(Map.entry("PLAYED", "재생 끝"),
            Map.entry("PARTIAL", "앞부분만 들림(나간 부분까지 재생 끝)"),
            Map.entry("INTERRUPTED", "재생 중 끊김"), Map.entry("FAILED", "재생 실패"), Map.entry("MISMATCH", "원문 불일치로 버림"),
            Map.entry("INCOMPLETE", "음성 미완"), Map.entry("BLOCKED", "제브가 막음"), Map.entry("TIMEOUT", "슬롯 안전 시계로 닫힘"),
            Map.entry("NOT_SENT", "요청을 보내지 못함"), Map.entry("CANCELLED", "흐름이 취소"),
            Map.entry("PLAYED_THEN_CANCELLED", "재생 뒤 취소"));
    private static final List<String> CLOCK_KINDS = List.of("NO_RESPONSE_ARMED", "NO_RESPONSE_CANCELLED", "NO_RESPONSE_FIRED",
            "CHILD_SPEECH_STARTED", "SAFETY_WAIT_ARMED", "SAFETY_WAIT_CANCELLED", "SAFETY_WAIT_FIRED", "SLOT_GUARD_FIRED", "REST_REQUESTED");

    private static final Pattern SAFE_TOKEN = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final JsonCodec JSON = JsonCodec.shared();
    private static final JsonMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final String NL = "\n";

    private PracticeVoiceReport() {
    }

    // ------------------------------------------------------------------ 작은 도구

    /** 순서 있는 지도(키, 값, 키, 값 …). 값은 null이어도 된다. */
    static Map<String, Object> ordered(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("키와 값의 쌍이 맞지 않는다");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    /** 대화 시작부터의 ms → [분:초.1]. */
    static String formatMs(long ms) {
        long tenths = Math.max(0, Math.round(ms / 100.0));
        return String.format(Locale.ROOT, "[%02d:%02d.%d]", tenths / 600, (tenths % 600) / 10, tenths % 10);
    }

    /** 짧은 식별 토큰만 남긴다(모델 이름 등). 그 밖은 null. */
    static String safeToken(String value) {
        return value != null && SAFE_TOKEN.matcher(value).matches() ? value : null;
    }

    static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** 운영 값(연습 기록 등)을 JSON 지도 · 목록으로 바꾼다. 못 바꾸면 종류만 남긴다. */
    static Object plainOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.read(JSON.write(value), Object.class);
        } catch (RuntimeException unreadable) {
            return ordered("unreadable", value.getClass().getSimpleName());
        }
    }

    /** 승인 문장 대조 열쇠: 공백을 빼고 끝 문장부호를 뗀다(흐름은 문장 끝에 마침표를 채워 읽는다). */
    static String lineKey(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", "").replaceAll("[.?!,。！？…\"”’']+$", "");
    }

    private static String secs(Long ms) {
        return ms == null ? "-" : String.format(Locale.ROOT, "%.1f초", ms / 1000.0);
    }

    private static String nz(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String cell(Object value) {
        return value == null ? "-" : String.valueOf(value).replace("|", "∣").replaceAll("[\\r\\n]+", " ");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int num(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String flowLabel(Flow flow) {
        return flow == null ? null : flow.label();
    }

    /** 승인 원문 읽기 시도의 자리(첫 시도 · 나머지 다시 읽기 · 같은 글 재시도 · '뜻 전하기' 복구). */
    static String attemptLabel(Attempt attempt) {
        if (attempt.reading == null) {
            return attempt.approved() ? "승인 원문 읽기" : "생성형";
        }
        int nth = attempt.indexInReading + 1;
        if (attempt.recovery) {
            return "'뜻 전하기' 복구(" + nth + "번째 시도)";
        }
        if (attempt.resumed) {
            return "나머지 다시 읽기(" + nth + "번째 시도)";
        }
        return nth == 1 ? "승인 원문 읽기 첫 시도" : "같은 글 재시도(" + nth + "번째 시도)";
    }

    static String endLabel(String end) {
        return end == null ? "끝 기록 없음(열린 채 세션 끝)" : END_LABELS.getOrDefault(end, end);
    }

    /** 시도의 끝 이름. 부분 재생이면 원문의 몇 글자까지 들렸는지 붙인다(위치만, 실행부 사실 값). */
    static String endLabel(Attempt attempt) {
        String label = endLabel(attempt.end);
        return attempt.partial() && attempt.heardEnd != null && attempt.text != null
                ? label + "(원문 " + attempt.heardEnd + "/" + attempt.text.length() + "자)" : label;
    }

    /** 버린 사유 코드 → 이름(원문 불일치 · 새 말 없음 · 빈 구간 등). */
    static String discardLabel(String code) {
        if (code == null) {
            return "?";
        }
        if (code.endsWith("APPROVED_TEXT_MISMATCH")) {
            return "원문 불일치";
        }
        if (code.equals("STAGE/FIRST_OUTPUT_TIMEOUT")) {
            return "새 말 없음";
        }
        if (code.startsWith("GATE_EMPTY")) {
            return "빈 구간";
        }
        if (code.startsWith("STAGE/")) {
            return "음성 단계 시한(" + code.substring("STAGE/".length()) + ")";
        }
        if (code.startsWith("GUARDRAIL_BLOCKED")) {
            return "제브가 막음";
        }
        if (code.equals("SLOT_GUARD")) {
            return "슬롯 안전 시계";
        }
        return code;
    }

    static Map<String, Object> stats(List<Long> values) {
        List<Long> sorted = values.stream().filter(Objects::nonNull).sorted().toList();
        if (sorted.isEmpty()) {
            return ordered("count", 0);
        }
        return ordered("count", sorted.size(), "minMs", sorted.getFirst(), "medianMs", rank(sorted, 0.5), "p90Ms", rank(sorted, 0.9),
                "maxMs", sorted.getLast());
    }

    static Map<String, Object> waitStats(List<Long> values) {
        Map<String, Object> stats = stats(values);
        if (num(stats.get("count")) > 0) {
            stats.put("over3sCount", values.stream().filter(v -> v != null && v > WAIT_SLOW_MS).count());
            stats.put("over6sCount", values.stream().filter(v -> v != null && v > WAIT_LONG_MS).count());
        }
        return stats;
    }

    private static long rank(List<Long> sorted, double quantile) {
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static String statsLine(Map<String, Object> stats) {
        if (num(stats.get("count")) == 0) {
            return "없음";
        }
        String line = stats.get("count") + "번 · 중앙 " + secs(asLong(stats.get("medianMs"))) + " · 90% " + secs(asLong(stats.get("p90Ms")))
                + " · 최대 " + secs(asLong(stats.get("maxMs")));
        if (stats.containsKey("over3sCount")) {
            line += " · 3초 넘음 " + stats.get("over3sCount") + "번 · 6초 넘음 " + stats.get("over6sCount") + "번";
        }
        return line;
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    // ------------------------------------------------------------------ 대본 칸 이름

    /**
     * 흐름이 낼 장면 안 승인 문장 → 대본 칸 이름(변형 장면은 '변형:' 앞머리) · 고정 code. 흐름과 같은 {@link DirectAskLines}로 만든다(시작 패키지
     * approvedSpeech와 같은 고정 문장).
     */
    static Map<String, String> lineLabels(PracticeScript script, Map<String, String> approvedSpeech) {
        return lineLabels(script, approvedSpeech, null);
    }

    /**
     * {@link #lineLabels(PracticeScript, Map)}에 장면 밖 목표 안내 승인 문장(편차 FT-1024-g)을 '목표 안내'로 더한다. 하니스 시작 패키지는 목표 하나라
     * 순서 1 · 목표 수 1로 만든다. 목표 이름을 승인 문장으로 다듬을 수 없으면(흐름이 생성형으로 낸다) 더하지 않는다.
     */
    static Map<String, String> lineLabels(PracticeScript script, Map<String, String> approvedSpeech, String goalName) {
        DirectAskLines lines = new DirectAskLines(approvedSpeech);
        Map<String, String> labels = new LinkedHashMap<>();
        String goalBrief = DirectAskLines.goalBrief(goalName, 1, 1);
        if (goalBrief != null) {
            label(labels, goalBrief, "목표 안내");
        }
        for (Scene scene : script.scenes()) {
            String prefix = scene.sceneKind() == SceneKind.VARIANT ? "변형:" : "";
            String ask = present(scene.askPrompt()) ? "askPrompt" : DirectAskLines.ASK_DEFAULT;
            String relay = present(scene.opportunityRelay()) ? "opportunityRelay" : "대체:opportunitySentence";
            label(labels, lines.introduction(scene), prefix + (present(scene.spokenIntroduction()) ? "spokenIntroduction" : "대체:상황 안내"));
            label(labels, lines.opportunity(scene), prefix + relay + "+" + ask);
            label(labels, lines.waitAndAsk(scene), prefix + DirectAskLines.WAIT_LEAD + "+" + ask);
            label(labels, lines.reexplanation(scene), prefix + "reexplanation+" + ask);
            label(labels, lines.reask(scene), prefix + (present(scene.reaskPrompt()) ? "reaskPrompt" : "대체:되묻기"));
            label(labels, lines.waitAndReask(scene), prefix + DirectAskLines.WAIT_LEAD + "+" + (present(scene.reaskPrompt()) ? "reaskPrompt" : "대체:되묻기"));
            label(labels, lines.help(scene).text(), prefix + (present(scene.helpSpeech()) ? "helpSpeech" : "대체:도움"));
            label(labels, lines.friendReaction(scene), prefix + (present(scene.friendReaction()) ? "friendReaction" : "대체:친구 반응"));
        }
        for (String code : List.of(DirectAskLines.HELP_OFFER, DirectAskLines.VARIANT_OFFER, DirectAskLines.RETRY_PROMPT,
                DirectAskLines.INPUT_CONFIRM, DirectAskLines.SCENE_CLOSE)) {
            labels.putIfAbsent(lineKey(lines.fixed(code)), code);
        }
        return labels;
    }

    private static void label(Map<String, String> labels, String text, String name) {
        String key = lineKey(text);
        if (key.isEmpty()) {
            return;
        }
        labels.merge(key, name, (old, added) -> old.equals(added) || old.contains(added) ? old : old + " 또는 " + added);
    }

    /** 목표 행의 장면 설명: 장면마다 아이가 무중에게서 들을 승인 문장(대본 칸)과 빠진 C안 칸. */
    static Map<String, Object> sceneReport(PracticeScript script) {
        DirectAskLines lines = new DirectAskLines(Map.of());
        List<Map<String, Object>> scenes = new ArrayList<>();
        for (Scene scene : script.scenes()) {
            Map<String, Object> heard = new LinkedHashMap<>();
            heard.put("spokenIntroduction", lines.introduction(scene));
            heard.put("opportunityRelay+askPrompt", lines.opportunity(scene));
            heard.put("reaskPrompt", lines.reask(scene));
            heard.put("reexplanation+askPrompt", lines.reexplanation(scene));
            heard.put("helpSpeech", lines.help(scene).text());
            heard.put("friendReaction", lines.friendReaction(scene));
            List<String> missing = new ArrayList<>();
            if (!present(scene.spokenIntroduction())) {
                missing.add("spokenIntroduction");
            }
            if (!present(scene.opportunityRelay())) {
                missing.add("opportunityRelay");
            }
            if (!present(scene.askPrompt())) {
                missing.add("askPrompt");
            }
            if (!present(scene.reaskPrompt())) {
                missing.add("reaskPrompt");
            }
            if (!present(scene.helpSpeech())) {
                missing.add("helpSpeech");
            }
            if (!present(scene.friendReaction())) {
                missing.add("friendReaction");
            }
            scenes.add(ordered("sceneKind", scene.sceneKind().name(), "sceneKey", scene.sceneKey(), "partnerRole", scene.partnerRole(),
                    "relation", scene.relation(), "sceneDescription", scene.sceneDescription(), "expressionExamples",
                    scene.expressionExamples(), "missingDirectAskFields", missing, "heardFromMujoong", heard));
        }
        return ordered("scenarioId", script.scenarioId().toString(), "scriptVersion", script.scriptVersion(), "hasVariant",
                script.variantScene().isPresent(), "scenes", scenes);
    }

    // ------------------------------------------------------------------ 쓰기

    /** 들여쓴 JSON으로 쓴다(쓰지 못하면 그 사실만 쓴다). */
    static void writePretty(Path file, Object value) throws IOException {
        String text;
        try {
            text = PRETTY.writeValueAsString(value);
        } catch (RuntimeException failure) {
            text = "{\"outcome\":\"REPORT_WRITE_FAILED\",\"failure\":\"" + failure.getClass().getSimpleName() + "\"}";
        }
        Files.writeString(file, text + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 대화 하나

    /** 대화 하나의 보고(JSON 한 줄 · 요약이 읽는 수치 · Markdown 절). */
    record DialogueRun(Map<String, Object> row, Metrics metrics) {
    }

    /**
     * 요약 표가 읽는 대화 하나의 수치(대화 기록 Markdown 절 포함). {@code partialPlays}는 앞부분만 들리고 끝난 승인 원문 시도 수,
     * {@code rereadReadings}는 C안이 부분 재생 뒤 나머지를 다시 읽은 읽기 수다(읽기 결과와 따로 센다).
     */
    record Metrics(int index, String goal, String goalName, String plan, String planLabel, String status, String scenes,
            List<Map<String, Object>> sceneRows, Map<String, Integer> readings, Map<String, Integer> readingDiscards, int readingAttempts,
            int partialPlays, int rereadReadings, List<Map<String, Object>> waits, List<Map<String, Object>> silentWaits,
            List<Map<String, Object>> relayJudgments, List<Map<String, Object>> judgments, Map<String, Integer> clock,
            int noResponseMalfunctions, List<Map<String, Object>> malfunctions, List<Map<String, Object>> autoResponses,
            Map<String, Integer> guardDecisions, List<Long> guardLatencies, double usageSeconds, double connectedSeconds, double seconds,
            boolean gptLiveStarted, int childTurnsSpoken, int transcriptMismatches, String markdown) {

        List<Long> waitValues() {
            return waits.stream().map(row -> asLong(row.get("waitMs"))).filter(Objects::nonNull).toList();
        }

        Metrics withMarkdown(String text) {
            return new Metrics(index, goal, goalName, plan, planLabel, status, scenes, sceneRows, readings, readingDiscards, readingAttempts,
                    partialPlays, rereadReadings, waits, silentWaits, relayJudgments, judgments, clock, noResponseMalfunctions, malfunctions,
                    autoResponses, guardDecisions, guardLatencies, usageSeconds, connectedSeconds, seconds, gptLiveStarted, childTurnsSpoken,
                    transcriptMismatches, text);
        }
    }

    /** 대화 하나의 보고를 만든다. 만들다 실패해도 대화는 남기고 그 사실을 적는다. */
    static DialogueRun dialogue(PracticeVoiceHarness harness, PracticeVoiceHarness.DialoguePlan plan, GoalLines lines, Snapshot snapshot,
            String status, String endRequestedFor, Map<String, Object> session, List<Map<String, Object>> prepared, double seconds) {
        String evidence = harness.backends == null ? null : harness.backends.evidence();
        try {
            return new Dialogue(plan, lines, snapshot, status, endRequestedFor, session, prepared, seconds, evidence).build();
        } catch (RuntimeException failure) {
            Map<String, Object> row = ordered("index", plan.index(), "goal", plan.goal().key(), "plan", plan.plan().name(), "status", status,
                    "session", session, "reportError", failure.getClass().getSimpleName());
            Metrics metrics = new Metrics(plan.index(), plan.goal().key(), plan.goal().goalName(), plan.plan().name(), plan.plan().label,
                    status, "보고 실패", List.of(), Map.of(), Map.of(), 0, 0, 0, List.of(), List.of(), List.of(), List.of(), Map.of(), 0,
                    List.of(), List.of(), Map.of(), List.of(), snapshot.usageSeconds(), 0, seconds, snapshot.providerStartedAtMs() != null, 0, 0,
                    "- 대화 보고를 만들지 못했다(" + failure.getClass().getSimpleName() + "). JSON의 사실을 본다." + NL);
            return new DialogueRun(row, metrics);
        }
    }

    /** 무중이 소리를 낸 때 하나(다음 무중 말 찾기용). */
    private record Out(long atMs, Long requestedAtMs, String source, String kind) {
    }

    /** 아이 차례 하나와 그 차례에 이어진 사실(확정 전사 · 흐름 수신 · 판정 · 다음 무중 말). */
    private record TurnLink(ChildTurn turn, List<Row> rows, Heard heard, Long firstDeltaMs, List<JudgeCall> judgments, Out next, Long waitMs,
            boolean childFirst) {

        String transcript() {
            if (rows.isEmpty()) {
                return null;
            }
            List<String> texts = new ArrayList<>();
            for (Row row : rows) {
                if (present(row.text())) {
                    texts.add(row.text());
                }
            }
            return texts.isEmpty() ? "" : String.join(" ", texts);
        }

        Long confirmedMs() {
            return rows.isEmpty() ? null : rows.getFirst().atMs();
        }
    }

    /** 대화 기록 한 줄(시각 순으로 늘어놓는다). */
    private record Entry(long atMs, int order, Map<String, Object> json, String markdown) {
    }

    private static final class Dialogue {

        private final PracticeVoiceHarness.DialoguePlan plan;
        private final GoalLines lines;
        private final Snapshot s;
        private final String status;
        private final String endRequestedFor;
        private final Map<String, Object> session;
        private final List<Map<String, Object>> prepared;
        private final double seconds;
        private final String evidence;
        private final Map<Integer, List<Play>> playsByAttempt = new HashMap<>();
        private final List<Play> autoPlays = new ArrayList<>();
        private final List<Out> outs = new ArrayList<>();
        private final List<ChildTurn> spoken = new ArrayList<>();
        private final Map<Integer, TurnLink> links = new LinkedHashMap<>();
        private final List<Entry> timeline = new ArrayList<>();
        private final Map<Integer, List<GuardCheck>> guardsByAttempt = new HashMap<>();

        Dialogue(PracticeVoiceHarness.DialoguePlan plan, GoalLines lines, Snapshot s, String status, String endRequestedFor,
                Map<String, Object> session, List<Map<String, Object>> prepared, double seconds, String evidence) {
            this.plan = plan;
            this.lines = lines;
            this.s = s;
            this.status = status;
            this.endRequestedFor = endRequestedFor;
            this.session = session == null ? Map.of() : session;
            this.prepared = prepared == null ? List.of() : prepared;
            this.seconds = seconds;
            this.evidence = evidence;
        }

        DialogueRun build() {
            indexPlays();
            linkGuards();
            linkTurns();
            buildTimeline();
            List<Map<String, Object>> readings = readings();
            Map<String, Integer> readingCounts = new LinkedHashMap<>();
            READING_OUTCOMES.forEach(outcome -> readingCounts.put(outcome, 0));
            Map<String, Integer> readingDiscards = new LinkedHashMap<>();
            int readingAttempts = 0;
            int partialPlays = 0;
            int rereadReadings = 0;
            for (Reading reading : s.readings()) {
                readingCounts.merge(reading.outcome(), 1, Integer::sum);
                partialPlays += (int) reading.partialPlays();
                rereadReadings += reading.reread() ? 1 : 0;
                for (Attempt attempt : reading.attempts) {
                    readingAttempts++;
                    for (String code : attempt.discards) {
                        readingDiscards.merge(discardLabel(code), 1, Integer::sum);
                    }
                    if (!attempt.heard() && attempt.end != null && attempt.discards.isEmpty() && !"PLAYED".equals(attempt.end)) {
                        readingDiscards.merge(endLabel(attempt.end), 1, Integer::sum);
                    }
                }
            }
            List<Map<String, Object>> waits = waits();
            List<Map<String, Object>> silentWaits = silentWaits();
            List<Map<String, Object>> judgments = judgments();
            List<Map<String, Object>> relay = relayJudgments();
            List<Map<String, Object>> sceneRows = sceneRows();
            Map<String, Integer> clock = new LinkedHashMap<>();
            CLOCK_KINDS.forEach(kind -> clock.put(kind, 0));
            for (ClockEvent event : s.clockEvents()) {
                clock.merge(event.kind(), 1, Integer::sum);
            }
            List<Map<String, Object>> autoResponses = new ArrayList<>();
            for (Play play : autoPlays) {
                autoResponses.add(ordered("dialogue", plan.index(), "atMs", play.startedAtMs, "at", formatMs(play.startedAtMs), "text", play.text,
                        "durationMs", play.durationMs, "end", play.end));
            }
            Map<String, Integer> guardDecisions = new LinkedHashMap<>();
            List<Long> guardLatencies = new ArrayList<>();
            for (GuardCheck check : s.guardChecks()) {
                guardDecisions.merge(nz(check.decision, "NO_RESULT"), 1, Integer::sum);
                if (check.latencyMs >= 0) {
                    guardLatencies.add(check.latencyMs);
                }
            }
            int mismatches = 0;
            for (TurnLink link : links.values()) {
                String transcript = link.transcript();
                if (transcript != null && !lineKey(transcript).equals(lineKey(link.turn().text))) {
                    mismatches++;
                }
            }
            List<Map<String, Object>> malfunctions = new ArrayList<>();
            for (Map<String, Object> detail : s.malfunctionDetails()) {
                Map<String, Object> row = new LinkedHashMap<>(detail);
                row.put("dialogue", plan.index());
                malfunctions.add(row);
            }
            String scenesLine = scenesLine(sceneRows);
            Map<String, Object> row = row(readings, readingCounts, readingDiscards, waits, silentWaits, judgments, relay, sceneRows, clock,
                    autoResponses, guardDecisions, guardLatencies, mismatches);
            Metrics metrics = new Metrics(plan.index(), plan.goal().key(), plan.goal().goalName(), plan.plan().name(), plan.plan().label,
                    status, scenesLine, sceneRows, readingCounts, readingDiscards, readingAttempts, partialPlays, rereadReadings, waits,
                    silentWaits, relay, judgments, clock, s.noResponseMalfunctions(), malfunctions, autoResponses, guardDecisions, guardLatencies,
                    s.usageSeconds(), connectedSeconds(), seconds, s.providerStartedAtMs() != null, spoken.size(), mismatches, null);
            return new DialogueRun(row, metrics.withMarkdown(markdown(metrics, readings)));
        }

        private double connectedSeconds() {
            if (s.providerOpenedAtMs() == null) {
                return 0;
            }
            long end = s.providerClosedAtMs() != null ? s.providerClosedAtMs() : s.providerOpenedAtMs();
            return round1(Math.max(0, end - s.providerOpenedAtMs()) / 1000.0);
        }

        // -------------------------------------------------------------- 잇기

        private void indexPlays() {
            for (Play play : s.plays()) {
                if (play.gptLive()) {
                    if (play.attempt != null) {
                        playsByAttempt.computeIfAbsent(play.attempt.id, id -> new ArrayList<>()).add(play);
                    } else if (play.startedAtMs != null) {
                        autoPlays.add(play);
                    }
                } else if (play.startedAtMs != null) {
                    outs.add(new Out(play.startedAtMs, play.queuedAtMs, "승인 음성:" + play.code, "APPROVED_AUDIO"));
                }
            }
            for (Attempt attempt : s.attempts()) {
                Long heardAt = heardAt(attempt);
                if (heardAt != null) {
                    outs.add(new Out(heardAt, attempt.atMs, attempt.source, attempt.kind));
                }
            }
            for (Play play : autoPlays) {
                outs.add(new Out(play.startedAtMs, play.queuedAtMs, "GPT-Live 자동 응답", "AUTO_RESPONSE"));
            }
            outs.sort(Comparator.comparingLong(Out::atMs));
        }

        /** 제브 검사를 그때 열려 있던 가장 최근 말 요청에 붙인다. */
        private void linkGuards() {
            for (GuardCheck check : s.guardChecks()) {
                Attempt owner = null;
                for (Attempt attempt : s.attempts()) {
                    if (attempt.atMs <= check.atMs) {
                        owner = attempt;
                    }
                }
                if (owner != null) {
                    guardsByAttempt.computeIfAbsent(owner.id, id -> new ArrayList<>()).add(check);
                }
            }
        }

        /** 재생을 시작한 그 말 요청의 GPT-Live 묶음들(시각 순). */
        private List<Play> startedPlays(Attempt attempt) {
            List<Play> plays = new ArrayList<>();
            for (Play play : playsByAttempt.getOrDefault(attempt.id, List.of())) {
                if (play.startedAtMs != null) {
                    plays.add(play);
                }
            }
            plays.sort(Comparator.comparingLong(play -> play.startedAtMs));
            return plays;
        }

        /** 아이가 그 말을 듣기 시작한 때(기기 재생 시작, 없으면 흐름이 받은 재생 시작 사실). 안 들렸으면 null. */
        private Long heardAt(Attempt attempt) {
            List<Play> plays = startedPlays(attempt);
            if (!plays.isEmpty()) {
                return plays.getFirst().startedAtMs;
            }
            return attempt.startedAtMs;
        }

        /** 들린 글(그 말 요청의 GPT-Live 구간 확정 전사를 이어 붙인다, 응답 묶음 행이면 그 행의 글). */
        private String heardText(Attempt attempt) {
            List<String> texts = new ArrayList<>();
            for (Play play : startedPlays(attempt)) {
                if (present(play.text)) {
                    texts.add(play.text.strip());
                }
            }
            if (texts.isEmpty() && present(attempt.rowText)) {
                return attempt.rowText.strip();
            }
            return texts.isEmpty() ? null : String.join(" ", texts);
        }

        /** 세션 끝 한 줄(최종 상태 · 종료 사유 · 세부, 빈 값은 뺀다). */
        private String sessionLabel() {
            List<String> parts = new ArrayList<>();
            for (String key : List.of("finalStatus", "endReason", "endDetail")) {
                if (present(str(session.get(key)))) {
                    parts.add(str(session.get(key)));
                }
            }
            return parts.isEmpty() ? "?" : String.join(" ", parts);
        }

        private void linkTurns() {
            for (ChildTurn turn : s.childTurns()) {
                if (turn.spoke()) {
                    spoken.add(turn);
                }
            }
            List<Row> childRows = new ArrayList<>();
            for (Row row : s.rows()) {
                if (row.speaker() == TranscriptEntry.Speaker.CHILD) {
                    childRows.add(row);
                }
            }
            Set<Integer> used = new HashSet<>();
            for (int i = 0; i < spoken.size(); i++) {
                ChildTurn turn = spoken.get(i);
                long from = turn.speechStartMs - ROW_LEAD_MS;
                long to = i + 1 < spoken.size() ? spoken.get(i + 1).speechStartMs : Long.MAX_VALUE;
                List<Row> rows = new ArrayList<>();
                for (Row row : childRows) {
                    if (!used.contains(row.seq()) && row.atMs() >= from && row.atMs() < to) {
                        rows.add(row);
                        used.add(row.seq());
                    }
                }
                Heard heard = null;
                if (!rows.isEmpty()) {
                    for (Heard candidate : s.heard()) {
                        if (candidate.seq() == rows.getFirst().seq()) {
                            heard = candidate;
                            break;
                        }
                    }
                }
                if (heard == null) {
                    for (Heard candidate : s.heard()) {
                        if (candidate.atMs() >= from && candidate.atMs() < to) {
                            heard = candidate;
                            break;
                        }
                    }
                }
                Long firstDelta = rows.isEmpty() ? null : s.msOf(s.childFirstDelta().get(rows.getFirst().seq()));
                List<JudgeCall> judgments = new ArrayList<>();
                for (JudgeCall call : s.judgeCalls()) {
                    if (call.childTurn != null && call.childTurn == turn.index) {
                        judgments.add(call);
                    }
                }
                long end = turn.speechEndMs != null ? turn.speechEndMs : turn.speechStartMs;
                Out next = nextOut(end);
                Long nextChild = i + 1 < spoken.size() ? spoken.get(i + 1).speechStartMs : null;
                boolean childFirst = nextChild != null && (next == null || nextChild < next.atMs());
                Long wait = next == null || childFirst ? null : next.atMs() - end;
                links.put(turn.index, new TurnLink(turn, rows, heard, firstDelta, judgments, childFirst ? null : next, wait, childFirst));
            }
        }

        private Out nextOut(long afterMs) {
            for (Out out : outs) {
                if (out.atMs() > afterMs) {
                    return out;
                }
            }
            return null;
        }

        // -------------------------------------------------------------- 대화 기록

        private void add(long atMs, Map<String, Object> json, String markdown) {
            json.put("atMs", atMs);
            json.put("at", formatMs(atMs));
            timeline.add(new Entry(atMs, timeline.size(), json, markdown));
        }

        private void buildTimeline() {
            Set<Integer> unheardAttempts = new HashSet<>();
            for (Play play : s.plays()) {
                if (play.gptLive()) {
                    continue;
                }
                Map<String, Object> json = ordered("who", "무중", "kind", "APPROVED_AUDIO", "code", play.code, "text", play.text, "end", play.end,
                        "durationMs", play.durationMs);
                if (play.startedAtMs != null) {
                    String end = play.end == null || "COMPLETED".equals(play.end) ? "" : " · " + play.end;
                    add(play.startedAtMs, json, "무중: " + nz(play.text, "(문구 없음)") + " (기기 승인 음성 " + play.code + end + ")");
                } else {
                    json.put("kind", "APPROVED_AUDIO_NOT_PLAYED");
                    add(play.endedAtMs != null ? play.endedAtMs : play.queuedAtMs, json,
                            "(안 들림) 무중: 기기 승인 음성 " + play.code + " — " + nz(play.end, "재생 시작 전 끝"));
                }
            }
            for (Attempt attempt : s.attempts()) {
                Long heardAt = heardAt(attempt);
                String heardText = heardText(attempt);
                List<String> guards = new ArrayList<>();
                for (GuardCheck check : guardsByAttempt.getOrDefault(attempt.id, List.of())) {
                    guards.add("제브 " + check.mode + " " + nz(check.decision, "결과 없음") + (check.failure == null ? "" : "/" + check.failure)
                            + (check.latencyMs >= 0 ? " " + secs(check.latencyMs) : ""));
                }
                Map<String, Object> json = ordered("who", "무중", "kind", attempt.kind, "attemptId", attempt.id, "source", attempt.source,
                        "readingId", attempt.reading == null ? null : attempt.reading.id, "attempt", attemptLabel(attempt), "flow",
                        flowLabel(attempt.flow), "heard", heardAt != null, "text", heardText, "approvedText", attempt.approved() ? attempt.text : null,
                        "end", attempt.end, "heardEnd", attempt.heardEnd, "resumed", attempt.resumed, "discards", attempt.discards,
                        "requestToSoundMs", heardAt == null ? null : heardAt - attempt.atMs, "guard", guards);
                String guardNote = guards.isEmpty() ? "" : " · " + String.join(" · ", guards);
                if (heardAt != null) {
                    String shown = present(heardText) ? heardText
                            : attempt.approved() ? attempt.text + " [전사 행 없음 — 승인 원문을 적음]" : "(전사 없음)";
                    String result = attempt.reading == null ? "" : " → " + READING_LABELS.getOrDefault(attempt.reading.outcome(), "?");
                    String end = attempt.end == null || "PLAYED".equals(attempt.end) ? "" : " · " + endLabel(attempt);
                    add(heardAt, json, "무중: " + shown + " (" + attempt.source + " · " + attemptLabel(attempt) + result + " · 요청 → 첫 소리 "
                            + secs(heardAt - attempt.atMs) + end + guardNote + ")");
                } else {
                    unheardAttempts.add(attempt.id);
                    List<String> said = new ArrayList<>();
                    for (Discard discard : s.discards()) {
                        if (discard.attemptId != null && discard.attemptId == attempt.id && present(discard.text)) {
                            said.add("«" + discard.text + "»");
                        }
                    }
                    List<String> reasons = new ArrayList<>();
                    for (String code : attempt.discards) {
                        reasons.add(discardLabel(code) + "(" + code + ")");
                    }
                    String what = attempt.approved() ? "«" + attempt.text + "»" : "생성형 말";
                    String after = attempt.endedAtMs == null ? "" : secs(attempt.endedAtMs - attempt.atMs) + " 뒤 ";
                    add(attempt.atMs, json, "(안 들림) 무중: " + what + " (" + attempt.source + " · " + attemptLabel(attempt) + " · " + after
                            + endLabel(attempt.end) + (reasons.isEmpty() ? "" : " · 사유 " + String.join(", ", reasons))
                            + (said.isEmpty() ? "" : " · GPT-Live가 한 말 " + String.join(" ", said)) + guardNote + ")");
                }
            }
            for (Discard discard : s.discards()) {
                if (discard.attemptId != null && unheardAttempts.contains(discard.attemptId)) {
                    continue;
                }
                String code = discard.reason + "/" + discard.detail;
                add(discard.atMs, ordered("who", "무중", "kind", "DISCARDED_SEGMENT", "seq", discard.seq, "reason", discard.reason, "detail",
                        discard.detail, "text", discard.text, "attemptId", discard.attemptId, "phase", discard.phase, "sub", discard.sub,
                        "generatedSpeech", discard.generatedSpeech), "(버림) GPT-Live 구간 «" + nz(discard.text, "전사 없음") + "» — "
                        + discardLabel(code) + "(" + code + ")" + (discard.attemptId == null ? " · 연습 말 요청 밖" : " · 말 요청 #" + discard.attemptId)
                        + " · " + discard.phase + "/" + discard.sub);
            }
            for (Play play : autoPlays) {
                add(play.startedAtMs, ordered("who", "무중", "kind", "AUTO_RESPONSE", "text", play.text, "durationMs", play.durationMs, "end",
                        play.end), "무중(연습 말 요청 밖 GPT-Live 재생 — 자동 응답): «" + nz(play.text, "전사 없음") + "»");
            }
            for (ChildTurn turn : s.childTurns()) {
                childEntry(turn);
            }
            for (ClockEvent event : s.clockEvents()) {
                String text = switch (event.kind()) {
                    case "NO_RESPONSE_FIRED" -> "*무응답 시계 울림" + (event.delayMs() == null ? "" : "(걸린 지 " + secs(event.delayMs()) + ")")
                            + " · " + event.flow() + "*";
                    case "CHILD_SPEECH_STARTED" -> "*실행부 알림: 아이 말 시작(4회차 R1) · " + event.flow() + "*";
                    case "SAFETY_WAIT_ARMED" -> "*안전 대기 걸림(" + secs(event.delayMs()) + ")*";
                    case "SAFETY_WAIT_FIRED" -> "*안전 대기 울림*";
                    case "SAFETY_WAIT_CANCELLED" -> "*안전 대기 거둠*";
                    case "SLOT_GUARD_FIRED" -> "*슬롯 안전 시계 울림*";
                    case "REST_REQUESTED" -> "*쉬기 요청(" + event.name() + ")*";
                    default -> null;
                };
                Map<String, Object> json = ordered("who", "시계", "kind", event.kind(), "name", event.name(), "delayMs", event.delayMs(), "flow",
                        event.flow());
                if (text != null) {
                    add(event.atMs(), json, text);
                }
            }
            for (SceneRow scene : s.scenes()) {
                add(scene.startedAtMs, ordered("who", "장면", "kind", "SCENE_STARTED", "sceneKind", scene.sceneKind), "*장면 시작: " + scene.sceneKind
                        + "*");
                if (scene.endedAtMs != null) {
                    add(scene.endedAtMs, ordered("who", "장면", "kind", "SCENE_OUTCOME", "sceneKind", scene.sceneKind, "timing", scene.timing,
                            "variantSkipReason", scene.skipReason, "retryCount", scene.retryCount, "reexplainCount", scene.reexplainCount, "facts",
                            List.copyOf(scene.facts)), "*장면 결과: " + scene.sceneKind + " " + nz(scene.timing, "?")
                            + (scene.skipReason == null ? "" : " · 변형 생략 " + scene.skipReason) + " · 다시 해 보기 " + scene.retryCount + " · 다시 설명 "
                            + scene.reexplainCount + (scene.facts.isEmpty() ? "" : " · 사실 " + String.join(", ", scene.facts)) + "*");
                }
            }
            for (Map<String, Object> notice : s.notices()) {
                long at = asLong(notice.get("atMs")) == null ? 0 : asLong(notice.get("atMs"));
                add(at, ordered("who", "하니스", "kind", "NOTICE", "text", notice.get("text")), "*하니스: " + notice.get("text") + "*");
            }
            long last = timeline.stream().mapToLong(Entry::atMs).max().orElse(0);
            Map<String, Object> end = new LinkedHashMap<>(session);
            end.put("who", "세션");
            end.put("kind", "SESSION_END");
            end.put("endRequestedFor", endRequestedFor);
            add(last, end, "*세션 끝: " + sessionLabel() + (endRequestedFor == null ? "" : " · 하니스가 끝내기 요청(" + endRequestedFor + ")") + "*");
            timeline.sort(Comparator.comparingLong(Entry::atMs).thenComparingInt(Entry::order));
        }

        private void childEntry(ChildTurn turn) {
            Map<String, Object> json = ordered("who", "아이", "kind", "CHILD_" + turn.act, "turn", turn.index, "key", turn.key, "intended", turn.text,
                    "rule", turn.rule, "flow", flowLabel(turn.flow), "status", turn.status, "tts", turn.ttsOutcome, "ttsCached", turn.ttsCached,
                    "audioMs", turn.audioMs);
            if (PracticeVoiceChild.Act.SILENT.equals(turn.act)) {
                add(turn.cueAtMs, json, "아이: (일부러 말하지 않음 — " + turn.rule + ")");
                return;
            }
            if (!PracticeVoiceChild.Act.SPEAK.equals(turn.act)) {
                return;
            }
            if (!turn.spoke()) {
                add(turn.cueAtMs, json, "아이: (말하지 못함 — TTS " + nz(turn.ttsOutcome, "?") + ") 하려던 말 «" + turn.text + "»");
                return;
            }
            TurnLink link = links.get(turn.index);
            String transcript = link.transcript();
            json.put("transcript", transcript);
            json.put("transcriptSeq", link.rows().isEmpty() ? null : link.rows().getFirst().seq());
            json.put("speechStartMs", turn.speechStartMs);
            json.put("speechEndMs", turn.speechEndMs);
            json.put("firstTranscriptDeltaMs", link.firstDeltaMs());
            json.put("transcriptConfirmedMs", link.confirmedMs());
            json.put("flowReceivedMs", link.heard() == null ? null : link.heard().atMs());
            json.put("flowReceivedUncertain", link.heard() == null ? null : link.heard().uncertain());
            json.put("judgments", link.judgments().stream().map(PracticeVoiceReport::judgmentLabel).toList());
            json.put("nextMujoongAtMs", link.next() == null ? null : link.next().atMs());
            json.put("waitMs", link.waitMs());
            StringBuilder line = new StringBuilder("아이: ").append(turn.text);
            if (transcript == null) {
                line.append(" → 확정 전사 없음");
            } else {
                line.append(" → 들은 전사 «").append(transcript).append("»");
                if (!lineKey(transcript).equals(lineKey(turn.text))) {
                    line.append("(의도와 다름)");
                }
            }
            List<String> details = new ArrayList<>();
            details.add(turn.key + " · " + turn.rule);
            for (JudgeCall call : link.judgments()) {
                details.add(judgmentLabel(call));
            }
            if (link.firstDeltaMs() != null) {
                details.add("첫 전사 조각 " + formatMs(link.firstDeltaMs()));
            }
            if (link.confirmedMs() != null) {
                details.add("확정 " + formatMs(link.confirmedMs()));
            }
            if (link.heard() != null) {
                details.add("흐름 받음 " + formatMs(link.heard().atMs()) + (link.heard().uncertain() ? "(불확실)" : "")
                        + (link.heard().devicePlaying() ? "(재생 중 말함)" : ""));
            }
            if (link.waitMs() != null) {
                details.add("다음 무중 말까지 " + secs(link.waitMs()) + (link.waitMs() > WAIT_LONG_MS ? "(긴 대기)" : ""));
            } else if (link.childFirst()) {
                details.add("무중 말 전에 아이가 다시 말함");
            } else {
                details.add("뒤에 무중 말 없음");
            }
            line.append(" (").append(String.join(" · ", details)).append(")");
            add(turn.speechStartMs, json, line.toString());
        }

        // -------------------------------------------------------------- 표

        private List<Map<String, Object>> readings() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Reading reading : s.readings()) {
                List<Map<String, Object>> attempts = new ArrayList<>();
                for (Attempt attempt : reading.attempts) {
                    Long heardAt = heardAt(attempt);
                    attempts.add(ordered("attemptId", attempt.id, "kind", attempt.kind, "position", attemptLabel(attempt), "accepted",
                            attempt.accepted, "requestedAtMs", attempt.atMs, "requestSentAtMs", attempt.requestSentAtMs, "instructionAtMs",
                            attempt.instructionAtMs, "instructionType", attempt.instructionType, "firstOutputAtMs", attempt.firstOutputAtMs,
                            "heardAtMs", heardAt, "endedAtMs", attempt.endedAtMs, "end", attempt.end, "heardEnd", attempt.heardEnd, "resumed",
                            attempt.resumed, "text", attempt.text, "discards", attempt.discards, "stages", attempt.stages, "heardText",
                            heardText(attempt)));
                }
                rows.add(ordered("readingId", reading.id, "atMs", reading.atMs, "at", formatMs(reading.atMs), "source", reading.source, "text",
                        reading.text, "outcome", reading.outcome(), "outcomeLabel", READING_LABELS.get(reading.outcome()), "partialPlays",
                        reading.partialPlays(), "reread", reading.reread(), "attempts", attempts));
            }
            return rows;
        }

        private List<Map<String, Object>> generated() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Attempt attempt : s.attempts()) {
                if (attempt.approved() || attempt.recovery) {
                    continue;
                }
                Long heardAt = heardAt(attempt);
                List<Map<String, Object>> guards = new ArrayList<>();
                for (GuardCheck check : guardsByAttempt.getOrDefault(attempt.id, List.of())) {
                    guards.add(ordered("mode", check.mode, "decision", check.decision, "failure", check.failure, "reasonCode", check.reasonCode,
                            "categories", check.categories, "latencyMs", check.latencyMs, "candidate", check.candidate));
                }
                rows.add(ordered("attemptId", attempt.id, "atMs", attempt.atMs, "at", formatMs(attempt.atMs), "source", attempt.source, "role",
                        attempt.role, "flow", flowLabel(attempt.flow), "accepted", attempt.accepted, "heard", heardAt != null, "heardAtMs", heardAt,
                        "requestToSoundMs", heardAt == null ? null : heardAt - attempt.atMs, "heardText", heardText(attempt), "end", attempt.end,
                        "discards", attempt.discards, "stages", attempt.stages, "guard", guards));
            }
            return rows;
        }

        private List<Map<String, Object>> judgments() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JudgeCall call : s.judgeCalls()) {
                TurnLink link = call.childTurn == null ? null : links.get(call.childTurn);
                rows.add(ordered("dialogue", plan.index(), "index", call.index, "atMs", call.atMs, "at", formatMs(call.atMs), "point", call.point,
                        "kind", call.kind, "first", call.first, "firstProbability", call.firstProbability, "second", call.second,
                        "secondProbability", call.secondProbability, "abstainReason", call.abstainReason, "sameStepSum", call.sameStepSum,
                        "requestedMinConfidence", call.requestedMinConfidence, "latencyMs", call.latencyMs,
                        "cancelledAtDeadline", call.cancelledAtDeadline, "harnessCapped", call.capped, "model", call.model, "uncertainInput",
                        call.uncertain, "childTurn", call.childTurn, "childKey", link == null ? null : link.turn().key, "childText",
                        link == null ? null : link.turn().text, "utterance", call.utterance));
            }
            return rows;
        }

        private List<Map<String, Object>> relayJudgments() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (TurnLink link : links.values()) {
                if (!"RELAY".equals(link.turn().key)) {
                    continue;
                }
                String scene = link.turn().flow == null ? null : link.turn().flow.sceneKind().name();
                boolean any = false;
                for (JudgeCall call : link.judgments()) {
                    if (!"J2".equals(call.point)) {
                        continue;
                    }
                    any = true;
                    rows.add(ordered("dialogue", plan.index(), "goal", plan.goal().key(), "plan", plan.plan().name(), "sceneKind", scene,
                            "childText", link.turn().text, "transcript", link.transcript(), "kind", call.kind, "first", call.first,
                            "firstProbability", call.firstProbability, "second", call.second, "secondProbability", call.secondProbability,
                            "abstainReason", call.abstainReason, "latencyMs", call.latencyMs, "label", judgmentLabel(call)));
                }
                if (!any) {
                    rows.add(ordered("dialogue", plan.index(), "goal", plan.goal().key(), "plan", plan.plan().name(), "sceneKind", scene,
                            "childText", link.turn().text, "transcript", link.transcript(), "kind", "NO_J2", "label", "J2 판정 없음"));
                }
            }
            return rows;
        }

        /** 아이 말 끝 → 다음 무중 말 시작(소리)과 그 사이 단계(확정 전사 · 흐름 수신 · 판정 끝 · 말 요청). */
        private List<Map<String, Object>> waits() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (TurnLink link : links.values()) {
                ChildTurn turn = link.turn();
                long end = turn.speechEndMs != null ? turn.speechEndMs : turn.speechStartMs;
                Long judgedAt = null;
                for (JudgeCall call : link.judgments()) {
                    if (call.doneAtMs != null && (link.next() == null || call.doneAtMs <= link.next().atMs())) {
                        judgedAt = judgedAt == null ? call.doneAtMs : Math.max(judgedAt, call.doneAtMs);
                    }
                }
                Out next = link.next();
                rows.add(ordered("dialogue", plan.index(), "turn", turn.index, "childKey", turn.key, "childText", turn.text, "flow",
                        flowLabel(turn.flow), "childEndMs", end, "nextMujoongAtMs", next == null ? null : next.atMs(), "nextSource",
                        next == null ? null : next.source(), "nextKind", next == null ? null : next.kind(), "waitMs", link.waitMs(),
                        "childSpokeAgainFirst", link.childFirst(), "confirmedAfterMs", after(link.confirmedMs(), end), "flowReceivedAfterMs",
                        after(link.heard() == null ? null : link.heard().atMs(), end), "judgmentDoneAfterMs", after(judgedAt, end),
                        "requestedAfterMs", next == null ? null : after(next.requestedAtMs(), end), "slow",
                        link.waitMs() != null && link.waitMs() > WAIT_SLOW_MS, "long", link.waitMs() != null && link.waitMs() > WAIT_LONG_MS));
            }
            return rows;
        }

        private static Long after(Long at, long base) {
            return at == null ? null : at - base;
        }

        /** 일부러 말하지 않은 차례: 무응답 시계 울림 → 다음 무중 말(다시 묻기) 시작. */
        private List<Map<String, Object>> silentWaits() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ChildTurn turn : s.childTurns()) {
                if (!PracticeVoiceChild.Act.SILENT.equals(turn.act)) {
                    continue;
                }
                Long firedAt = null;
                for (ClockEvent event : s.clockEvents()) {
                    if ("NO_RESPONSE_FIRED".equals(event.kind()) && event.atMs() >= turn.cueAtMs) {
                        firedAt = event.atMs();
                        break;
                    }
                }
                Out next = firedAt == null ? null : nextOut(firedAt);
                rows.add(ordered("dialogue", plan.index(), "turn", turn.index, "cueAtMs", turn.cueAtMs, "noResponseFiredAtMs", firedAt,
                        "firedAfterCueMs", firedAt == null ? null : firedAt - turn.cueAtMs, "nextMujoongAtMs", next == null ? null : next.atMs(),
                        "nextSource", next == null ? null : next.source(), "waitAfterFireMs", next == null ? null : next.atMs() - firedAt));
            }
            return rows;
        }

        private List<Map<String, Object>> sceneRows() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (SceneRow scene : s.scenes()) {
                rows.add(ordered("sceneKind", scene.sceneKind, "timing", scene.timing, "variantSkipReason", scene.skipReason, "retryCount",
                        scene.retryCount, "reexplainCount", scene.reexplainCount, "facts", List.copyOf(scene.facts), "startedAtMs", scene.startedAtMs,
                        "endedAtMs", scene.endedAtMs, "outcomeCalls", scene.outcomeCalls));
            }
            return rows;
        }

        private static String scenesLine(List<Map<String, Object>> rows) {
            if (rows.isEmpty()) {
                return "장면 없음";
            }
            List<String> parts = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String timing = row.get("timing") == null ? "미완" : str(row.get("timing"));
                String skip = row.get("variantSkipReason") == null ? "" : "(변형 생략 " + row.get("variantSkipReason") + ")";
                parts.add(row.get("sceneKind") + " " + timing + skip);
            }
            return String.join(" · ", parts);
        }

        // -------------------------------------------------------------- JSON 한 줄

        private Map<String, Object> row(List<Map<String, Object>> readings, Map<String, Integer> readingCounts,
                Map<String, Integer> readingDiscards, List<Map<String, Object>> waits, List<Map<String, Object>> silentWaits,
                List<Map<String, Object>> judgments, List<Map<String, Object>> relay, List<Map<String, Object>> sceneRows,
                Map<String, Integer> clock, List<Map<String, Object>> autoResponses, Map<String, Integer> guardDecisions, List<Long> guardLatencies,
                int mismatches) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", plan.index());
            row.put("goal", plan.goal().key());
            row.put("goalName", plan.goal().goalName());
            row.put("plan", plan.plan().name());
            row.put("planLabel", plan.plan().label);
            row.put("evidence", evidence);
            row.put("status", status);
            row.put("endRequestedFor", endRequestedFor);
            row.put("session", session);
            row.put("settled", s.settled());
            row.put("seconds", round1(seconds));
            row.put("gptLive", ordered("usageSeconds", round1(s.usageSeconds()), "usageSource", "공급자 보고(UsageUpdated 최댓값)",
                    "connectedSeconds", connectedSeconds(), "providerStartedAtMs", s.providerStartedAtMs(), "outputAudioBytes", s.outputAudioBytes(),
                    "outputTranscriptDeltas", s.outputTranscriptDeltas(), "inputTranscriptDeltas", s.inputTranscriptDeltas(), "errors",
                    s.providerErrors(), "instructionTypes", s.instructionTypes()));
            row.put("scenes", sceneRows);
            row.put("scenesLine", scenesLine(sceneRows));
            row.put("helps", s.helps());
            row.put("readingCounts", readingCounts);
            row.put("readingDiscards", readingDiscards);
            row.put("flowDiagnostics", s.flowDiagnostics());
            row.put("readings", readings);
            row.put("generatedOutsideScene", generated());
            row.put("judgments", judgments);
            row.put("judgmentRequests", s.judgmentRequests().stream().map(request -> ordered("atMs", request.atMs(), "point", request.point(),
                    "transcriptSeq", request.transcriptSeq(), "accepted", request.accepted(), "flow", flowLabel(request.flow()))).toList());
            row.put("judgmentEventsToFlow", s.judgmentEvents().stream().map(event -> ordered("atMs", event.atMs(), "kind", event.kind(), "point",
                    event.point(), "firstOption", event.firstOption(), "secondOption", event.secondOption(), "abstainReason",
                    event.abstainReason())).toList());
            row.put("relayJudgments", relay);
            row.put("mujoongWaits", waits);
            row.put("mujoongWaitStats", waitStats(waits.stream().map(wait -> asLong(wait.get("waitMs"))).toList()));
            row.put("silentWaits", silentWaits);
            row.put("noResponseClock", ordered("counts", clock, "malfunctions", s.noResponseMalfunctions(), "malfunctionDetails",
                    s.malfunctionDetails(), "events", s.clockEvents().stream().map(event -> ordered("atMs", event.atMs(), "kind", event.kind(),
                            "name", event.name(), "delayMs", event.delayMs(), "flow", event.flow())).toList()));
            row.put("gptLiveAutoResponsesHeard", ordered("count", autoResponses.size(), "items", autoResponses, "expected", 0));
            row.put("guardrail", ordered("checks", s.guardChecks().size(), "decisions", guardDecisions, "latency", stats(guardLatencies)));
            row.put("childLines", ordered("source", lines.source(), "fallbackReason", lines.call() == null ? null : lines.call().get("fallbackReason")));
            row.put("preparedChildAudio", prepared);
            row.put("childTurns", s.childTurns().stream().map(turn -> ordered("turn", turn.index, "cueAtMs", turn.cueAtMs, "act", turn.act, "key",
                    turn.key, "text", turn.text, "rule", turn.rule, "flow", flowLabel(turn.flow), "status", turn.status, "tts", turn.ttsOutcome,
                    "ttsLatencyMs", turn.ttsLatencyMs, "ttsCached", turn.ttsCached, "audioMs", turn.audioMs, "speechStartMs", turn.speechStartMs,
                    "speechEndMs", turn.speechEndMs, "sendEndMs", turn.sendEndMs)).toList());
            row.put("childTranscriptMismatches", mismatches);
            row.put("discards", s.discards().stream().map(discard -> ordered("atMs", discard.atMs, "seq", discard.seq, "reason", discard.reason,
                    "detail", discard.detail, "label", discardLabel(discard.reason + "/" + discard.detail), "text", discard.text, "attemptId",
                    discard.attemptId, "phase", discard.phase, "sub", discard.sub, "generatedSpeech", discard.generatedSpeech)).toList());
            row.put("transcriptRows", s.rows().stream().map(transcript -> ordered("seq", transcript.seq(), "speaker", transcript.speaker().name(),
                    "text", transcript.text(), "approved", transcript.approved(), "atMs", transcript.atMs(), "startedAtMs",
                    s.msOf(transcript.startedAt()), "endedAtMs", s.msOf(transcript.endedAt()), "reviewNeeded", transcript.reviewNeeded())).toList());
            row.put("flowTrace", s.flowTrace());
            row.put("signals", s.signals());
            row.put("flowFailures", s.flowFailures());
            row.put("appErrors", s.appErrors());
            row.put("notices", s.notices());
            row.put("otherInstructions", s.otherInstructions());
            row.put("outputCalls", s.outputCalls());
            row.put("deviceViews", s.deviceViews());
            row.put("frames", ordered("offered", s.framesOffered(), "voiced", s.voicedFramesOffered()));
            row.put("eventCounts", s.eventCounts());
            row.put("sessionEvents", s.sessionEvents());
            row.put("practiceRecord", s.practiceRecord());
            row.put("timeline", timeline.stream().map(Entry::json).toList());
            return row;
        }

        // -------------------------------------------------------------- Markdown 절

        private String markdown(Metrics m, List<Map<String, Object>> readings) {
            StringBuilder md = new StringBuilder();
            md.append("### 대화 ").append(m.index()).append(" · ").append(m.goal()).append(" × ").append(m.planLabel()).append("(")
                    .append(m.plan()).append(")").append(NL).append(NL);
            if (session.containsKey("notStarted")) {
                md.append("- 시작하지 않음: ").append(session.get("notStarted")).append(NL);
                appendPrepared(md);
                return md.toString();
            }
            md.append("- 상태: ").append(m.status()).append(" · 세션 ").append(sessionLabel())
                    .append(endRequestedFor == null ? "" : " · 하니스 끝내기 요청 " + endRequestedFor).append(" · 걸린 시간 ")
                    .append(round1(m.seconds())).append("초 · GPT-Live 사용 ").append(round1(m.usageSeconds())).append("초(공급자 보고, 연결 ")
                    .append(m.connectedSeconds()).append("초)").append(NL);
            md.append("- 장면 결과: ").append(m.scenes()).append(s.helps().isEmpty() ? "" : " · 도움 기록 " + s.helps().size() + "건").append(NL);
            md.append("- 승인 원문 읽기: ").append(readingLine(m.readings())).append(m.readingDiscards().isEmpty() ? ""
                    : " · 버린 사유 " + m.readingDiscards()).append(" · 부분 재생 ").append(m.partialPlays()).append("번 · 나머지 다시 읽은 읽기 ")
                    .append(m.rereadReadings()).append("개").append(NL);
            if (!s.flowDiagnostics().isEmpty()) {
                md.append("- 흐름 진단(고정 code): ").append(s.flowDiagnostics()).append(NL);
            }
            md.append("- 무중 차례 대기(아이 말 끝 → 다음 무중 말 시작): ").append(statsLine(waitStats(m.waitValues()))).append(NL);
            md.append("- 무응답 시계: 걸림 ").append(m.clock().get("NO_RESPONSE_ARMED")).append(" · 거둠 ").append(m.clock().get("NO_RESPONSE_CANCELLED"))
                    .append(" · 울림 ").append(m.clock().get("NO_RESPONSE_FIRED")).append(" · 아이 말 시작 알림(R1) ")
                    .append(m.clock().get("CHILD_SPEECH_STARTED")).append(" · 안전 대기 걸림 ").append(m.clock().get("SAFETY_WAIT_ARMED"))
                    .append(" · 오작동 ").append(m.noResponseMalfunctions()).append(NL);
            md.append("- 아이가 들은 GPT-Live 자동 응답: ").append(m.autoResponses().size()).append("(0이어야 한다)").append(NL);
            md.append("- 아이 말: ").append(lines.source()).append(" · 말한 차례 ").append(m.childTurnsSpoken()).append(" · 전사가 의도와 다름 ")
                    .append(m.transcriptMismatches()).append(NL);
            if (!s.flowFailures().isEmpty()) {
                md.append("- 흐름 오류 알림: ").append(String.join(", ", s.flowFailures())).append(NL);
            }
            if (!s.appErrors().isEmpty()) {
                md.append("- 앱 오류 로그: ").append(s.appErrors().size()).append("건 ").append(s.appErrors().stream()
                        .map(error -> str(error.get("errorKind"))).distinct().toList()).append(NL);
            }
            if (!s.providerErrors().isEmpty()) {
                md.append("- 공급자 오류 사건: ").append(s.providerErrors().stream().map(error -> str(error.get("kind"))).toList()).append(NL);
            }
            if (!s.otherInstructions().isEmpty()) {
                md.append("- 연습 말 요청 밖 공급자 지시: ").append(s.otherInstructions().size()).append("건(종류 ").append(s.otherInstructions().stream()
                        .map(item -> str(item.get("type"))).distinct().toList()).append(")").append(NL);
            }
            md.append(NL);
            for (Entry entry : timeline) {
                md.append("- ").append(formatMs(entry.atMs())).append(" ").append(entry.markdown()).append(NL);
            }
            md.append(NL);
            if (!readings.isEmpty()) {
                md.append("승인 원문 읽기").append(NL).append(NL);
                md.append("| 읽기 | 출처 | 결과 | 시도(끝 · 사유) |").append(NL).append("| --- | --- | --- | --- |").append(NL);
                for (Reading reading : s.readings()) {
                    List<String> attempts = new ArrayList<>();
                    for (Attempt attempt : reading.attempts) {
                        String heard = attempt.partial() ? endLabel(attempt)
                                : attempt.heard() || heardAt(attempt) != null ? "들림" : endLabel(attempt.end);
                        attempts.add("#" + attempt.id + " " + attemptLabel(attempt) + " " + heard + (attempt.discards.isEmpty() ? "" : "(" + attempt.discards.stream()
                                .map(PracticeVoiceReport::discardLabel).distinct().toList() + ")"));
                    }
                    md.append("| ").append(reading.id).append(" ").append(formatMs(reading.atMs)).append(" | ").append(cell(reading.source))
                            .append(" | ").append(READING_LABELS.getOrDefault(reading.outcome(), "?")).append(" | ")
                            .append(cell(String.join(" → ", attempts))).append(" |").append(NL);
                }
                md.append(NL);
            }
            List<Map<String, Object>> generated = generated();
            if (!generated.isEmpty()) {
                md.append("장면 밖 생성형 말").append(NL).append(NL);
                md.append("| 말 요청 | 출처 | 들림 | 요청 → 첫 소리 | 끝 · 사유 | 제브 |").append(NL).append("| --- | --- | --- | --- | --- | --- |")
                        .append(NL);
                for (Map<String, Object> item : generated) {
                    List<String> guards = new ArrayList<>();
                    for (Object guard : list(item.get("guard"))) {
                        Map<String, Object> g = map(guard);
                        guards.add(str(g.get("mode")) + " " + str(g.get("decision")) + (g.get("failure") == null ? "" : "/" + g.get("failure")));
                    }
                    md.append("| #").append(item.get("attemptId")).append(" ").append(item.get("at")).append(" | ").append(cell(item.get("source")))
                            .append(" | ").append(Boolean.TRUE.equals(item.get("heard")) ? "들림" : "안 들림").append(" | ")
                            .append(secs(asLong(item.get("requestToSoundMs")))).append(" | ").append(cell(endLabel((String) item.get("end"))))
                            .append(list(item.get("discards")).isEmpty() ? "" : cell(" · " + list(item.get("discards")))).append(" | ")
                            .append(guards.isEmpty() ? "-" : cell(String.join(", ", guards))).append(" |").append(NL);
                }
                md.append(NL);
            }
            if (!s.judgeCalls().isEmpty()) {
                md.append("판정(운영 Decisions 경로 · 실제 Luna)").append(NL).append(NL);
                md.append("| 판정 | 지점 | 아이 말 → 판정 입력 | 결과 | 2순위 | 지연 |").append(NL).append("| --- | --- | --- | --- | --- | --- |").append(NL);
                for (JudgeCall call : s.judgeCalls()) {
                    TurnLink link = call.childTurn == null ? null : links.get(call.childTurn);
                    String intended = link == null ? "-" : link.turn().key + " «" + link.turn().text + "»";
                    md.append("| ").append(call.index).append(" ").append(formatMs(call.atMs)).append(" | ").append(call.point).append(" | ")
                            .append(cell(intended + " → «" + nz(call.utterance, "") + "»" + (call.uncertain ? "(불확실)" : ""))).append(" | ")
                            .append(cell(resultLabel(call) + floorNote(call))).append(" | ").append(call.second == null ? "-" : call.second + " " + probability(
                                    call.secondProbability)).append(" | ").append(call.latencyMs < 0 ? "-" : secs(call.latencyMs)).append(" |")
                            .append(NL);
                }
                md.append(NL);
            }
            appendPrepared(md);
            return md.toString();
        }

        private void appendPrepared(StringBuilder md) {
            if (prepared.isEmpty()) {
                return;
            }
            int made = 0;
            int cached = 0;
            int failed = 0;
            for (Map<String, Object> item : prepared) {
                if (Boolean.TRUE.equals(item.get("cached"))) {
                    cached++;
                } else if ("OK".equals(item.get("outcome"))) {
                    made++;
                } else {
                    failed++;
                }
            }
            md.append("- 미리 만든 아이 말 소리(TTS, 대화 전): ").append(prepared.size()).append("줄 — 새로 만듦 ").append(made).append(" · 앞 대화 것 ")
                    .append(cached).append(" · 실패 ").append(failed).append(NL).append(NL);
        }
    }

    private static String readingLine(Map<String, Integer> counts) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return "없음";
        }
        List<String> parts = new ArrayList<>();
        for (String outcome : READING_OUTCOMES) {
            parts.add(READING_LABELS.get(outcome) + " " + counts.getOrDefault(outcome, 0));
        }
        return total + "개 — " + String.join(" · ", parts);
    }

    private static String probability(Double value) {
        return value == null ? "" : String.format(Locale.ROOT, "%.2f", value);
    }

    private static String resultLabel(JudgeCall call) {
        if (call.capped) {
            return "하니스 상한으로 부르지 않음(보류 처리)";
        }
        if (call.cancelledAtDeadline) {
            return "시한 취소(CANCELLED_AT_DEADLINE)";
        }
        if ("CONFIRMED".equals(call.kind) && call.sameStepSum && call.firstProbability != null && call.secondProbability != null) {
            return call.first + " " + probability(call.firstProbability) + "(같은 걸음 " + call.second + "와 합 "
                    + probability(call.firstProbability + call.secondProbability) + ")";
        }
        if ("CONFIRMED".equals(call.kind)) {
            return call.first + " " + probability(call.firstProbability);
        }
        if ("ABSTAINED".equals(call.kind)) {
            return "보류 " + nz(call.abstainReason, "?") + (call.first == null ? "" : "(1순위 " + call.first + " " + probability(call.firstProbability) + ")");
        }
        return nz(call.kind, "결과 없음");
    }

    /** 판정 한 줄(대화 기록용): J2 FULL 0.91(2순위 PARTIAL 0.05) · 1.2초. 요청 하한이 실렸으면 '(요청 하한 0.45)'를 붙인다(편차 FT-1024-h). */
    static String judgmentLabel(JudgeCall call) {
        String second = call.second == null ? "" : "(2순위 " + call.second + " " + probability(call.secondProbability) + ")";
        return call.point + " " + resultLabel(call) + ("CONFIRMED".equals(call.kind) ? second : "") + floorNote(call)
                + (call.latencyMs < 0 ? "" : " · " + secs(call.latencyMs));
    }

    /** 판정 요청에 실린 낮춘 하한 표시(C안 기회 J2, 편차 FT-1024-h). 없으면 빈 글. */
    private static String floorNote(JudgeCall call) {
        return call.requestedMinConfidence == null ? "" : "(요청 하한 " + probability(call.requestedMinConfidence) + ")";
    }

    // ------------------------------------------------------------------ 요약

    /** 여러 대화의 요약 표(JSON). 만들다 실패하면 그 사실만 남긴다. */
    static Map<String, Object> summary(List<DialogueRun> runs) {
        try {
            return summaryOf(runs.stream().map(DialogueRun::metrics).toList());
        } catch (RuntimeException failure) {
            return ordered("summaryError", failure.getClass().getSimpleName());
        }
    }

    private static Map<String, Object> summaryOf(List<Metrics> all) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> dialogues = new ArrayList<>();
        Map<String, List<Map<String, Object>>> byPlan = new LinkedHashMap<>();
        Map<String, Integer> readings = new LinkedHashMap<>();
        READING_OUTCOMES.forEach(outcome -> readings.put(outcome, 0));
        Map<String, Integer> discards = new LinkedHashMap<>();
        List<Long> waits = new ArrayList<>();
        List<Long> silentWaits = new ArrayList<>();
        Map<String, List<Long>> waitsBySource = new LinkedHashMap<>();
        Map<String, List<Long>> stageAfterEnd = new LinkedHashMap<>();
        List<Map<String, Object>> relay = new ArrayList<>();
        Map<String, Integer> clock = new LinkedHashMap<>();
        CLOCK_KINDS.forEach(kind -> clock.put(kind, 0));
        int malfunctions = 0;
        List<Map<String, Object>> malfunctionDetails = new ArrayList<>();
        List<Map<String, Object>> autoResponses = new ArrayList<>();
        Map<String, Map<String, Integer>> judgmentsByPoint = new LinkedHashMap<>();
        Map<String, List<Long>> judgmentLatencies = new LinkedHashMap<>();
        int cancelled = 0;
        int capped = 0;
        Map<String, Integer> guardDecisions = new LinkedHashMap<>();
        List<Long> guardLatencies = new ArrayList<>();
        double usage = 0;
        double connected = 0;
        int started = 0;
        int spokenTurns = 0;
        int mismatches = 0;
        int readingAttempts = 0;
        int partialPlays = 0;
        int rereadReadings = 0;
        for (Metrics m : all) {
            Map<String, Object> row = ordered("index", m.index(), "goal", m.goal(), "plan", m.plan(), "planLabel", m.planLabel(), "status",
                    m.status(), "scenes", m.scenes(), "readings", m.readings(), "partialPlays", m.partialPlays(), "rereadReadings",
                    m.rereadReadings(), "mujoongWait", waitStats(m.waitValues()), "noResponseMalfunctions", m.noResponseMalfunctions(),
                    "autoResponsesHeard", m.autoResponses().size(), "usageSeconds", round1(m.usageSeconds()));
            dialogues.add(row);
            byPlan.computeIfAbsent(m.plan(), plan -> new ArrayList<>()).add(ordered("index", m.index(), "goal", m.goal(), "status", m.status(),
                    "scenes", m.scenes()));
            m.readings().forEach((key, value) -> readings.merge(key, value, Integer::sum));
            m.readingDiscards().forEach((key, value) -> discards.merge(key, value, Integer::sum));
            readingAttempts += m.readingAttempts();
            partialPlays += m.partialPlays();
            rereadReadings += m.rereadReadings();
            for (Map<String, Object> wait : m.waits()) {
                Long value = asLong(wait.get("waitMs"));
                if (value == null) {
                    continue;
                }
                waits.add(value);
                waitsBySource.computeIfAbsent(sourceGroup(str(wait.get("nextSource"))), key -> new ArrayList<>()).add(value);
                for (String stage : List.of("confirmedAfterMs", "flowReceivedAfterMs", "judgmentDoneAfterMs", "requestedAfterMs")) {
                    Long stageValue = asLong(wait.get(stage));
                    if (stageValue != null) {
                        stageAfterEnd.computeIfAbsent(stage, key -> new ArrayList<>()).add(stageValue);
                    }
                }
            }
            for (Map<String, Object> wait : m.silentWaits()) {
                Long value = asLong(wait.get("waitAfterFireMs"));
                if (value != null) {
                    silentWaits.add(value);
                }
            }
            relay.addAll(m.relayJudgments());
            m.clock().forEach((key, value) -> clock.merge(key, value, Integer::sum));
            malfunctions += m.noResponseMalfunctions();
            malfunctionDetails.addAll(m.malfunctions());
            autoResponses.addAll(m.autoResponses());
            for (Map<String, Object> judgment : m.judgments()) {
                String point = str(judgment.get("point"));
                String result = Boolean.TRUE.equals(judgment.get("harnessCapped")) ? "HARNESS_CAPPED"
                        : Boolean.TRUE.equals(judgment.get("cancelledAtDeadline")) ? "CANCELLED_AT_DEADLINE"
                        : "CONFIRMED".equals(judgment.get("kind")) ? str(judgment.get("first"))
                        : "ABSTAINED".equals(judgment.get("kind")) ? "ABSTAINED:" + str(judgment.get("abstainReason")) : str(judgment.get("kind"));
                judgmentsByPoint.computeIfAbsent(point, key -> new LinkedHashMap<>()).merge(result, 1, Integer::sum);
                Long latency = asLong(judgment.get("latencyMs"));
                if (latency != null && latency >= 0 && !Boolean.TRUE.equals(judgment.get("harnessCapped"))) {
                    judgmentLatencies.computeIfAbsent(point, key -> new ArrayList<>()).add(latency);
                }
                if (Boolean.TRUE.equals(judgment.get("cancelledAtDeadline"))) {
                    cancelled++;
                }
                if (Boolean.TRUE.equals(judgment.get("harnessCapped"))) {
                    capped++;
                }
            }
            m.guardDecisions().forEach((key, value) -> guardDecisions.merge(key, value, Integer::sum));
            guardLatencies.addAll(m.guardLatencies());
            usage += m.usageSeconds();
            connected += m.connectedSeconds();
            started += m.gptLiveStarted() ? 1 : 0;
            spokenTurns += m.childTurnsSpoken();
            mismatches += m.transcriptMismatches();
        }
        int readingTotal = readings.values().stream().mapToInt(Integer::intValue).sum();
        summary.put("dialogues", dialogues);
        summary.put("scenesByPlan", byPlan);
        summary.put("approvedReadings", ordered("readings", readingTotal, "attempts", readingAttempts, "outcomes", readings, "outcomeLabels",
                READING_LABELS, "firstMatchRate", readingTotal == 0 ? null : round1(100.0 * readings.get(PracticeVoiceFacts.FIRST_MATCH)
                        / readingTotal), "partialPlays", partialPlays, "rereadReadings", rereadReadings, "discardReasons", discards));
        Map<String, Object> bySource = new LinkedHashMap<>();
        waitsBySource.forEach((key, values) -> bySource.put(key, waitStats(values)));
        Map<String, Object> stages = new LinkedHashMap<>();
        stageAfterEnd.forEach((key, values) -> stages.put(key, stats(values)));
        summary.put("mujoongWait", ordered("afterChildSpeech", waitStats(waits), "byNextSource", bySource, "stagesAfterChildEnd", stages,
                "afterNoResponseFire", waitStats(silentWaits), "rule", "아이 말 소리 끝(합성 음성의 마지막 유성 프레임) → 다음 무중 말 소리 시작(가상 기기 재생 시작). "
                        + "3초 · 6초는 하니스 참고 값"));
        summary.put("relayJ2", relay);
        summary.put("noResponseClock", ordered("counts", clock, "malfunctions", malfunctions, "malfunctionDetails", malfunctionDetails, "rule",
                "오작동 = 무응답 시계가 걸린 뒤 아이가 실제로 소리를 냈는데 그 시계가 무응답으로 울린 경우"));
        summary.put("gptLiveAutoResponsesHeard", ordered("count", autoResponses.size(), "expected", 0, "items", autoResponses));
        Map<String, Object> latencies = new LinkedHashMap<>();
        judgmentLatencies.forEach((key, values) -> latencies.put(key, stats(values)));
        summary.put("judgments", ordered("byPoint", judgmentsByPoint, "latencyByPoint", latencies, "cancelledAtDeadline", cancelled,
                "harnessCapped", capped));
        summary.put("guardrail", ordered("decisions", guardDecisions, "latency", stats(guardLatencies)));
        summary.put("gptLive", ordered("sessionsStarted", started, "usageSeconds", round1(usage), "connectedSeconds", round1(connected)));
        summary.put("childSpeech", ordered("spokenTurns", spokenTurns, "transcriptDiffersFromIntended", mismatches));
        summary.put("reportItem2LineName", "리포트 2항목 줄 이름은 core 재판정(rejudgment.v2)이 연습 기록으로 정한다. 대화마다 practiceRecord를 남겼다"
                + "(realtime 하니스는 core를 부르지 않는다, R-F5)");
        return summary;
    }

    /** 다음 무중 말 출처 묶음(대기 분포를 칸 · 단계별로 본다). */
    private static String sourceGroup(String source) {
        if (source.startsWith("생성형:")) {
            return source.contains("RECOVERY") ? "생성형:뜻 전하기 복구" : source;
        }
        return source.replace("변형:", "");
    }

    // ------------------------------------------------------------------ Markdown 전체

    /** 사람이 읽는 대화 기록(머리 · 요약 표 · 목표와 대본 · 대화마다 기록 · 건너뛴 대화). */
    static String markdown(Map<String, Object> top, List<DialogueRun> runs, List<Map<String, Object>> skipped) {
        List<Metrics> all = runs.stream().map(DialogueRun::metrics).toList();
        Map<String, Object> totals = map(top.get("totals"));
        Map<String, Object> caps = map(top.get("caps"));
        Map<String, Object> source = map(top.get("source"));
        Map<String, Object> guardrail = map(map(top.get("setup")).get("guardrail"));
        StringBuilder md = new StringBuilder();
        md.append("# 연습 음성 대화 기록 (C안 '무중이 직접 묻기' · GPT-Live 실음성 · 합성 아이 음성)").append(NL).append(NL);
        md.append("- 증거 종류: ").append(str(top.get("evidence"))).append(" — ").append(str(top.get("evidenceLabel")))
                .append(". 실제 기기 · 실제 아이 결과가 아니다(R-M11).").append(NL);
        md.append("- 결과: ").append(str(top.get("outcome"))).append(top.get("abortReason") == null ? "" : "(" + top.get("abortReason") + ")")
                .append(" · 시각: ").append(str(top.get("startedAt"))).append(" ~ ").append(str(top.get("finishedAt"))).append(NL);
        if (!guardrail.isEmpty()) {
            md.append("- 제브: ").append(str(guardrail.get("mode"))).append(" (env 있음 여부 ").append(str(guardrail.get("envPresent")))
                    .append(" · 정책 판 ").append(str(guardrail.get("policyVersion"))).append(" · 시한 ").append(str(guardrail.get("jevBudget")))
                    .append(list(guardrail.get("startupProblems")).isEmpty() ? "" : " · 기동 점검 " + guardrail.get("startupProblems")).append(")")
                    .append(NL);
        }
        md.append("- 합계: 대화 ").append(str(totals.get("dialoguesRan"))).append("건(끝까지 ").append(str(totals.get("dialoguesCompleted")))
                .append(" · 건너뜀 ").append(str(totals.get("dialoguesSkipped"))).append(") · GPT-Live 세션 ").append(str(totals.get("gptLiveSessions")))
                .append("개(").append(str(totals.get("gptLiveSecondsCounted"))).append("초 셈) · OpenAI 텍스트 호출 ").append(str(totals.get("openAiTextCalls")))
                .append("회(판정 ").append(str(totals.get("judgmentCalls"))).append(" · 아이 말 ").append(str(totals.get("childLineCalls")))
                .append(", 상한으로 막힘 ").append(str(totals.get("textCallsRefusedByCap"))).append(") · TTS ").append(str(totals.get("ttsCalls")))
                .append("회(상한으로 막힘 ").append(str(totals.get("ttsCallsRefusedByCap"))).append(", 실패 ").append(str(totals.get("ttsFailures")))
                .append(")").append(totals.containsKey("stoppedEarly") ? " · 일찍 멈춤 " + totals.get("stoppedEarly") : "").append(NL);
        md.append("- 상한: 대화당 ").append(str(caps.get("dialogueSeconds"))).append("초 · GPT-Live 합계 ").append(str(caps.get("gptLiveSecondsTotal")))
                .append("초 · 대화 ").append(str(caps.get("dialogues"))).append("건 · TTS ").append(str(caps.get("ttsCalls"))).append("회 · 판정 + 아이 말 ")
                .append(str(caps.get("judgmentAndChildLineCalls"))).append("회").append(NL);
        md.append("- 입력: ").append(str(source.get("kind"))).append(" ").append(list(source.get("files"))).append(source.get("warning") == null ? ""
                : " — " + source.get("warning")).append(NL);
        md.append("- 읽는 법: [분:초]는 대화 시작부터의 시간이다. '무중:' 줄은 아이가 실제로 들은 말(가상 기기가 재생을 시작한 GPT-Live 구간의 확정 전사 · "
                + "기기 승인 음성 문구)이고, 괄호에 출처(대본 칸 · 고정 code · 생성형 단계)와 승인 원문 읽기 결과를 적었다. '(안 들림)' · '(버림)'은 아이에게 "
                + "가지 않은 말과 사유다. '아이:' 줄은 하려던 말 → 실행부가 확정한 입력 전사, 괄호에 판정 · 전사 시각 · 다음 무중 말까지의 대기다.").append(NL);
        for (Object note : list(top.get("notes"))) {
            md.append("- ").append(str(note)).append(NL);
        }
        md.append(NL);

        md.append("## 요약").append(NL).append(NL);
        md.append("### 대화별 장면 결과").append(NL).append(NL);
        md.append("| 대화 | 목표 | 계획 | 상태 | 장면 결과 | 승인 읽기(첫 · 나머지 · 재 · 복구 · 부분 · 실패 · 미완) | 부분 재생 · 나머지 다시 읽기 "
                + "| 무중 대기 중앙 · 최대 | 무응답 오작동 | 자동 응답 |").append(NL);
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |").append(NL);
        for (Metrics m : all) {
            Map<String, Object> wait = waitStats(m.waitValues());
            md.append("| ").append(m.index()).append(" | ").append(cell(m.goal())).append(" | ").append(cell(m.planLabel())).append(" | ")
                    .append(cell(m.status())).append(" | ").append(cell(m.scenes())).append(" | ").append(readingCells(m.readings())).append(" | ")
                    .append(m.partialPlays()).append(" · ").append(m.rereadReadings()).append(" | ")
                    .append(num(wait.get("count")) == 0 ? "-" : secs(asLong(wait.get("medianMs"))) + " · " + secs(asLong(wait.get("maxMs"))))
                    .append(" | ").append(m.noResponseMalfunctions()).append(" | ").append(m.autoResponses().size()).append(" |").append(NL);
        }
        md.append(NL);

        Map<String, Object> summary = summary(runs);
        Map<String, Object> readings = map(summary.get("approvedReadings"));
        Map<String, Object> outcomes = map(readings.get("outcomes"));
        md.append("### 승인 원문 읽기").append(NL).append(NL);
        md.append("- 읽기 ").append(str(readings.get("readings"))).append("개(시도 ").append(str(readings.get("attempts"))).append("번): ");
        List<String> parts = new ArrayList<>();
        for (String outcome : READING_OUTCOMES) {
            parts.add(READING_LABELS.get(outcome) + " " + num(outcomes.get(outcome)));
        }
        md.append(String.join(" · ", parts)).append(readings.get("firstMatchRate") == null ? "" : " · 첫 시도 성공률 " + readings.get("firstMatchRate") + "%")
                .append(NL);
        md.append("- 부분 재생(앞부분만 들리고 끝난 시도): ").append(str(readings.get("partialPlays"))).append("번 · C안이 나머지를 다시 읽은 읽기: ")
                .append(str(readings.get("rereadReadings"))).append("개").append(NL);
        md.append("- 버린 사유(읽기 시도): ").append(map(readings.get("discardReasons")).isEmpty() ? "없음" : str(readings.get("discardReasons")))
                .append(NL).append(NL);

        Map<String, Object> waits = map(summary.get("mujoongWait"));
        md.append("### 무중 차례 대기").append(NL).append(NL);
        md.append("- 아이 말 끝 → 다음 무중 말 시작: ").append(statsLine(map(waits.get("afterChildSpeech")))).append(NL);
        Map<String, Object> stages = map(waits.get("stagesAfterChildEnd"));
        if (!stages.isEmpty()) {
            md.append("- 아이 말 끝부터(중앙): 전사 확정 ").append(secs(asLong(map(stages.get("confirmedAfterMs")).get("medianMs"))))
                    .append(" · 흐름 받음 ").append(secs(asLong(map(stages.get("flowReceivedAfterMs")).get("medianMs"))))
                    .append(" · 판정 끝 ").append(secs(asLong(map(stages.get("judgmentDoneAfterMs")).get("medianMs"))))
                    .append(" · 다음 말 요청 ").append(secs(asLong(map(stages.get("requestedAfterMs")).get("medianMs")))).append(NL);
        }
        md.append("- 무응답 시계 울림 → 다시 묻는 무중 말 시작: ").append(statsLine(map(waits.get("afterNoResponseFire")))).append(NL);
        Map<String, Object> bySource = map(waits.get("byNextSource"));
        if (!bySource.isEmpty()) {
            md.append(NL).append("| 다음 무중 말 | 대기 |").append(NL).append("| --- | --- |").append(NL);
            bySource.forEach((key, value) -> md.append("| ").append(cell(key)).append(" | ").append(statsLine(map(value))).append(" |").append(NL));
        }
        md.append(NL);

        md.append("### 전하는 식 대답의 J2 판정").append(NL).append(NL);
        List<Object> relay = list(summary.get("relayJ2"));
        if (relay.isEmpty()) {
            md.append("- 전하는 식 대답 없음").append(NL).append(NL);
        } else {
            md.append("| 대화 | 장면 | 아이 말 | 들은 전사 | J2 결과 |").append(NL).append("| --- | --- | --- | --- | --- |").append(NL);
            for (Object item : relay) {
                Map<String, Object> row = map(item);
                md.append("| ").append(str(row.get("dialogue"))).append(" | ").append(cell(row.get("sceneKind"))).append(" | ")
                        .append(cell(row.get("childText"))).append(" | ").append(cell(row.get("transcript"))).append(" | ").append(cell(row.get("label")))
                        .append(" |").append(NL);
            }
            md.append(NL);
        }

        Map<String, Object> clock = map(summary.get("noResponseClock"));
        Map<String, Object> clockCounts = map(clock.get("counts"));
        md.append("### 무응답 시계").append(NL).append(NL);
        md.append("- 걸림 ").append(num(clockCounts.get("NO_RESPONSE_ARMED"))).append(" · 거둠 ").append(num(clockCounts.get("NO_RESPONSE_CANCELLED")))
                .append(" · 울림 ").append(num(clockCounts.get("NO_RESPONSE_FIRED"))).append(" · 아이 말 시작 알림(R1) ")
                .append(num(clockCounts.get("CHILD_SPEECH_STARTED"))).append(" · 안전 대기 걸림 ").append(num(clockCounts.get("SAFETY_WAIT_ARMED")))
                .append(" · 안전 대기 울림 ").append(num(clockCounts.get("SAFETY_WAIT_FIRED"))).append(NL);
        md.append("- 무응답 오작동(시계가 걸린 뒤 아이가 소리를 냈는데 무응답으로 울림): ").append(num(clock.get("malfunctions")))
                .append(list(clock.get("malfunctionDetails")).isEmpty() ? "" : " " + clock.get("malfunctionDetails")).append(NL).append(NL);

        Map<String, Object> auto = map(summary.get("gptLiveAutoResponsesHeard"));
        md.append("### 아이가 들은 GPT-Live 자동 응답(0이어야 한다)").append(NL).append(NL);
        md.append("- ").append(num(auto.get("count"))).append("번");
        for (Object item : list(auto.get("items"))) {
            Map<String, Object> row = map(item);
            md.append(NL).append("  - 대화 ").append(str(row.get("dialogue"))).append(" ").append(str(row.get("at"))).append(" «")
                    .append(str(row.get("text"))).append("»");
        }
        md.append(NL).append(NL);

        Map<String, Object> judgments = map(summary.get("judgments"));
        md.append("### 판정").append(NL).append(NL);
        Map<String, Object> byPoint = map(judgments.get("byPoint"));
        Map<String, Object> latencyByPoint = map(judgments.get("latencyByPoint"));
        if (byPoint.isEmpty()) {
            md.append("- 판정 없음").append(NL);
        } else {
            md.append("| 지점 | 결과 분포 | 지연 |").append(NL).append("| --- | --- | --- |").append(NL);
            byPoint.forEach((point, results) -> md.append("| ").append(point).append(" | ").append(cell(results)).append(" | ")
                    .append(statsLine(map(latencyByPoint.get(point)))).append(" |").append(NL));
        }
        md.append("- 판정 턴 시한 취소 ").append(num(judgments.get("cancelledAtDeadline"))).append("번 · 하니스 상한으로 부르지 않음 ")
                .append(num(judgments.get("harnessCapped"))).append("번").append(NL).append(NL);

        Map<String, Object> guard = map(summary.get("guardrail"));
        md.append("### 제브 검사").append(NL).append(NL);
        md.append("- 결정 ").append(map(guard.get("decisions")).isEmpty() ? "없음" : str(guard.get("decisions"))).append(" · 지연 ")
                .append(statsLine(map(guard.get("latency")))).append(NL).append(NL);

        Map<String, Object> gptLive = map(summary.get("gptLive"));
        Map<String, Object> child = map(summary.get("childSpeech"));
        md.append("### 그 밖").append(NL).append(NL);
        md.append("- GPT-Live: 세션 시작 ").append(str(gptLive.get("sessionsStarted"))).append("개 · 사용 ").append(str(gptLive.get("usageSeconds")))
                .append("초(공급자 보고 합) · 연결 ").append(str(gptLive.get("connectedSeconds"))).append("초").append(NL);
        md.append("- 아이 말: 말한 차례 ").append(str(child.get("spokenTurns"))).append(" · 입력 전사가 의도와 다름 ")
                .append(str(child.get("transcriptDiffersFromIntended"))).append("(참고, 글자 비교)").append(NL);
        md.append("- ").append(str(summary.get("reportItem2LineName"))).append(NL).append(NL);

        if (!skipped.isEmpty()) {
            md.append("### 건너뛴 대화").append(NL).append(NL);
            md.append("| 순번 | 목표 | 계획 | 사유 |").append(NL).append("| --- | --- | --- | --- |").append(NL);
            for (Map<String, Object> row : skipped) {
                md.append("| ").append(str(row.get("index"))).append(" | ").append(cell(row.get("goal"))).append(" | ").append(cell(row.get("plan")))
                        .append(" | ").append(cell(row.get("skipReason"))).append(" |").append(NL);
            }
            md.append(NL);
        }

        md.append("## 목표와 대본").append(NL).append(NL);
        for (Object item : list(top.get("goals"))) {
            appendGoal(md, map(item));
        }

        md.append("## 대화 기록").append(NL).append(NL);
        if (all.isEmpty()) {
            md.append("- 돌린 대화 없음").append(NL);
        }
        for (Metrics m : all) {
            md.append(m.markdown()).append(NL);
        }
        return md.toString();
    }

    private static String readingCells(Map<String, Integer> readings) {
        List<String> values = new ArrayList<>();
        for (String outcome : READING_OUTCOMES) {
            values.add(String.valueOf(readings.getOrDefault(outcome, 0)));
        }
        return String.join(" · ", values);
    }

    private static void appendGoal(StringBuilder md, Map<String, Object> goal) {
        Map<String, Object> info = map(goal.get("goal"));
        md.append("### ").append(str(goal.get("key"))).append(" — ").append(nz(str(info.get("displayName")), "?")).append(" (")
                .append(str(goal.get("result"))).append(")").append(NL).append(NL);
        md.append("- 판정 기준: ").append(nz(str(info.get("judgmentCriteria")), "-")).append(NL);
        md.append("- 시작 패키지: ").append(str(goal.get("startPackageFile"))).append(" · 대본 ").append(nz(str(goal.get("scriptVersion")), "?"))
                .append(goal.get("rejection") == null ? "" : " · 거절 " + goal.get("rejection")).append(NL);
        Map<String, Object> scenes = map(goal.get("scenes"));
        for (Object item : list(scenes.get("scenes"))) {
            Map<String, Object> scene = map(item);
            md.append("- ").append(str(scene.get("sceneKind"))).append(" 장면(상대 ").append(str(scene.get("partnerRole"))).append(")에서 아이가 들을 말")
                    .append(list(scene.get("missingDirectAskFields")).isEmpty() ? "" : " — 빠진 칸 " + scene.get("missingDirectAskFields") + "(대체 문장)")
                    .append(NL);
            map(scene.get("heardFromMujoong")).forEach((name, text) -> md.append("  - [").append(name).append("] ").append(str(text)).append(NL));
        }
        Map<String, Object> childLines = map(goal.get("childLines"));
        if (!childLines.isEmpty()) {
            md.append("- 아이 말(하늘이, ").append(str(childLines.get("source"))).append(")").append(NL);
            for (String part : List.of("base", "variant")) {
                Map<String, Object> scene = map(childLines.get(part));
                if (scene.isEmpty()) {
                    continue;
                }
                List<String> texts = new ArrayList<>();
                scene.forEach((field, value) -> {
                    Map<String, Object> line = map(value);
                    texts.add(field + " «" + str(line.get("text")) + "»" + ("FALLBACK".equals(line.get("source")) ? "(대체)" : ""));
                });
                md.append("  - ").append(part).append(": ").append(String.join(" · ", texts)).append(NL);
            }
        }
        md.append(NL);
    }
}
