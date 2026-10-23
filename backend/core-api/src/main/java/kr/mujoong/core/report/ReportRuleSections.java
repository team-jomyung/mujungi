package kr.mujoong.core.report;

import static kr.mujoong.core.result.ResultData.*;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import kr.mujoong.core.result.PracticeRecordV2;
import kr.mujoong.core.result.SceneChipMapper;
import kr.mujoong.core.result.SceneJudgmentRepository;
import kr.mujoong.core.result.SceneReportMapper;
import kr.mujoong.core.result.check.ForbiddenDictionary;
import org.springframework.stereotype.Component;

/**
 * 2 · 5 · 6 · 10항목을 AI 없이 저장된 기록 · 결과로 만든다(JIRA-267, contracts/deviations.md FT-1023-c).
 *
 * <p>자료가 부족한 항목은 다른 설명 · 대체 내용 없이 "자료 부족"만 낸다.
 * 2항목은 연습 장면 판정이 가리키는 아이 발화만, 5항목은 이번 대화 자체가 아닌 실제 생활 경험 구조화만,
 * 6항목은 결과 문장이 있는 활동만 낸다. 10항목의 연습 고정 문장은 연습 활동이 있을 때만 붙인다.
 * 인용 글은 잡음을 걷어 낸 글이고({@link ReportQuotes}), 한 항목 안에서 같은 (세션, 발화) 인용은 한 번만 단다.
 * 동의 철회로 끝난 활동의 전사 · 결과는 어느 항목에도 넣지 않는다.
 */
@Component
public class ReportRuleSections {
    // 임시 문구(J118): 화면 설계 문구표에 리포트 항목 본문 문구가 없어 둔다. 문구표 개정 때 바꾼다.
    static final String FREE_TALK = "자유대화";
    static final String PRACTICE = "연습";
    static final String SCENE = "장면";
    static final String PENDING = "정리 중인 활동";
    static final String FAILED = "정리하지 못한 활동";
    static final String UNCONFIRMED = "종료가 확인되지 않은 활동";
    static final String END_REASONS = "끝난 이유";
    static final String NOT_DONE_SCENES = "미실시 장면";
    /** 경험 구조화 칸 이름. 순서가 5항목 줄 순서다. */
    static final Map<String, String> FIELD_NAMES = orderedFieldNames();
    /** 끝난 이유 표시 문구. 27 기록 화면(EndReasonCard)과 같은 문장이다. */
    static final Map<String, String> END_REASON_TEXT = Map.of(
            "GUARDIAN_STOP", "보호자가 끝냈어요",
            "CHILD_STOP", "아이가 끝냈어요",
            "SILENCE", "말이 없어 자동으로 끝났어요",
            "DISCONNECT", "연결이 끊겨 끝났어요",
            "CONSENT_WITHDRAWN", "동의가 철회되어 끝났어요");
    static final String END_REASON_UNKNOWN = "활동이 끝났어요";
    static final String END_REASON_NO_DEVICE = "기기 응답 없이 끝냈어요";

    /** 금지 표현 1차 사전 시험이 검사하는 고정 문구 전부(ForbiddenDictionaryTest). */
    public static final List<String> FIXED_PHRASES = fixedPhrases();

    private static final int SCENE_QUOTES = 2;
    private static final int ACTIVITY_QUOTES = 5;
    private static final int SECTION_QUOTES = 30;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M/d").withZone(ReportService.ZONE);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ReportService.ZONE);

    /** 이번 대화의 두 참여자(아이 · AI 무중)를 가리키는 등장인물 별칭. 비교는 소문자 · 공백 제거 뒤에 한다. */
    static final Set<String> SELF_ALIASES = Set.of("아이", "나", "저", "ai", "에이아이", "인공지능", "무중", "무중이");

    private final ForbiddenDictionary dictionary;

    public ReportRuleSections(ForbiddenDictionary dictionary) {
        this.dictionary = dictionary;
    }

    private record SceneQuote(ReportQuotes.Quote quote, String label) {}

    public Map<Integer, ReportRepository.Section> write(List<ReportInput> inputs) {
        Map<Integer, ReportRepository.Section> sections = new TreeMap<>();
        sections.put(2, observedScenes(inputs));
        sections.put(5, experiences(inputs));
        sections.put(6, conversations(inputs));
        sections.put(10, limitations(inputs));
        return sections;
    }

    /** 2항목: 연습 장면 판정의 아이 발화만. 없으면 자료 부족(자유대화 대체 인용 없음). */
    ReportRepository.Section observedScenes(List<ReportInput> inputs) {
        var ordered = practiceQuotes(inputs).stream()
                .sorted(Comparator.comparing((SceneQuote q) -> q.quote().at())
                        .thenComparingInt(q -> q.quote().activityNo())
                        .thenComparingLong(q -> q.quote().utteranceSeq()))
                .toList();
        List<String> lines = ordered.stream().map(q -> MINUTE.format(q.quote().at()) + " · " + q.label()).toList();
        return section(2, lines, ordered.stream().map(q -> q.quote().citation()).toList());
    }

    /** 연습 활동마다 장면 판정 순서로 아이 발화를 모은다. 활동은 최근부터, 활동당 5개 · 항목 30개까지다(BR9.7과 같은 상한). */
    private List<SceneQuote> practiceQuotes(List<ReportInput> inputs) {
        List<SceneQuote> result = new ArrayList<>();
        var practices = inputs.stream()
                .filter(i -> i.done() && "PRACTICE".equals(i.record().activityType()))
                .sorted(Comparator.comparing((ReportInput i) -> i.record().listedAt()).reversed().thenComparingInt(ReportInput::activityNo))
                .toList();
        for (var input : practices) {
            var transcript = transcript(input.record().transcriptJson());
            var child = childUtterances(transcript);
            var practice = practice(input.record().practiceJson());
            boolean modern = PracticeRecordV2.present(practice);
            var runs = modern ? PracticeRecordV2.read(practice) : List.<PracticeRecordV2.Scene>of();
            Map<String, Map<String, Object>> goals = new HashMap<>();
            for (var goal : goals(practice)) {
                goals.put(text(goal, "goalSelectionId"), goal);
            }
            Set<Long> taken = new HashSet<>();
            for (var scene : input.scenes()) {
                var goal = goals.getOrDefault(String.valueOf(scene.goalSelectionId()), Map.of());
                var seqs = modern ? modernSeqs(scene, runs, transcript) : legacySeqs(scene, goal);
                String label = text(goal, "displayName") + " · " + SCENE + " " + scene.sceneNo() + " · " + SceneReportMapper.text(scene);
                for (long seq : seqs) {
                    if (taken.size() == ACTIVITY_QUOTES) {
                        break;
                    }
                    var utterance = child.get(seq);
                    if (utterance != null && taken.add(seq)) {
                        result.add(new SceneQuote(ReportQuotes.quote(input, utterance, "TRANSCRIPT"), label));
                        if (result.size() == SECTION_QUOTES) {
                            return result;
                        }
                    }
                }
            }
        }
        return result;
    }

    /** U5b 형식: 장면 1은 FIRST, 장면 2는 NEW_SCENARIO 반응의 발화, 이어서 같은 상황의 도움 뒤(AFTER_CUE) 반응 발화. */
    private static List<Long> legacySeqs(SceneJudgmentRepository.Scene scene, Map<String, Object> goal) {
        String type = scene.sceneNo() == 1 ? "FIRST" : "NEW_SCENARIO";
        var responses = objects(goal.get("responses"));
        var primary = responses.stream().filter(r -> type.equals(r.get("responseType"))).findFirst().orElse(Map.of());
        List<Long> seqs = new ArrayList<>();
        if (number(primary, "utteranceSeq") > 0) {
            seqs.add(number(primary, "utteranceSeq"));
        }
        Object scenario = primary.get("scenarioId");
        if (scenario != null) {
            responses.stream()
                    .filter(r -> "AFTER_CUE".equals(r.get("responseType")) && scenario.equals(r.get("scenarioId")))
                    .map(r -> number(r, "utteranceSeq"))
                    .filter(seq -> seq > 0)
                    .sorted()
                    .forEach(seqs::add);
        }
        return seqs;
    }

    /**
     * 새 연습 흐름 형식: 장면 판정에 발화 번호가 없어, 그 장면 시작부터 다음 장면 시작 전까지 말한 아이 발화를 쓴다.
     * 내용 있는 발화를 먼저 골라 장면당 2개까지다.
     */
    private static List<Long> modernSeqs(SceneJudgmentRepository.Scene scene, List<PracticeRecordV2.Scene> runs,
            List<Map<String, Object>> transcript) {
        var run = runs.stream().filter(r -> r.sceneRunId().equals(scene.sceneRunId())).findFirst().orElse(null);
        if (run == null) {
            return List.of();
        }
        Instant from = run.startedAt();
        Instant to = runs.stream().map(PracticeRecordV2.Scene::startedAt).filter(at -> at.isAfter(from)).min(Comparator.naturalOrder()).orElse(null);
        return transcript.stream()
                .filter(ReportRuleSections::quotable)
                .filter(u -> {
                    Instant at = startedAt(u);
                    return at != null && !at.isBefore(from) && (to == null || at.isBefore(to));
                })
                .sorted(Comparator.comparing((Map<String, Object> u) -> !ReportQuotes.meaningful(text(u, "text"))).thenComparingLong(u -> number(u, "seq")))
                .limit(SCENE_QUOTES)
                .map(u -> number(u, "seq"))
                .sorted()
                .toList();
    }

    /**
     * 5항목: 저장된 경험 구조화 중 실제 생활 경험만. 등장인물이 아이 · AI뿐이고 상대가 한 말이 없는 구조화는 이번 대화 자체로 보고 뺀다.
     * 아이 회상 칸은 칸 글과 그 발화 인용을, 보호자 입력 칸은 줄 없이 그 구조화가 남을 때만 quote 없는 출처 인용 하나를 단다.
     */
    ReportRepository.Section experiences(List<ReportInput> inputs) {
        List<String> lines = new ArrayList<>();
        List<ReportRepository.Citation> citations = new ArrayList<>();
        Set<String> cited = new HashSet<>();
        for (var input : chronological(inputs)) {
            if (!input.done() || input.structured().isEmpty() || conversationItself(input.structured())) {
                continue;
            }
            var child = childUtterances(transcript(input.record().transcriptJson()));
            String day = DAY.format(input.record().listedAt());
            int before = lines.size();
            boolean guardian = false;
            for (var field : FIELD_NAMES.entrySet()) {
                for (var row : rows(input.structured().get(field.getKey()))) {
                    String source = text(row, "source");
                    if ("GUARDIAN_INPUT".equals(source)) {
                        guardian = true;
                        continue;
                    }
                    var utterance = "CHILD_RECALL".equals(source) ? child.get(number(row, "utteranceSeq")) : null;
                    String body = utterance == null ? "" : recallText(field.getKey(), row, utterance);
                    if (body.isEmpty()) {
                        continue;
                    }
                    lines.add(day + " · " + field.getValue() + body);
                    var quote = ReportQuotes.quote(input, utterance, "CHILD_RECALL");
                    if (cited.add(quote.sessionId() + ":" + quote.utteranceSeq())) {
                        citations.add(quote.citation());
                    }
                }
            }
            if (guardian && lines.size() > before) {
                citations.add(new ReportRepository.Citation(input.record().sessionId(), null, null, null, "GUARDIAN_INPUT"));
            }
        }
        return section(5, lines, citations);
    }

    /** 등장인물이 있고 모두 이번 대화의 참여자(아이 · AI)이며 상대가 한 말이 없으면 이번 대화 자체를 구조화한 것이다. */
    static boolean conversationItself(Map<String, Object> structured) {
        var characters = rows(structured.get("characters"));
        boolean selfOnly = !characters.isEmpty() && characters.stream().allMatch(c -> selfAlias(text(c, "alias")));
        boolean partner = rows(structured.get("partnerUtterance")).stream().anyMatch(r -> !text(r, "text").isBlank());
        return selfOnly && !partner;
    }

    static boolean selfAlias(String alias) {
        return SELF_ALIASES.contains(alias.toLowerCase(Locale.ROOT).replaceAll("\\s+", ""));
    }

    /**
     * 아이 회상 칸의 줄 글. 칸 글은 근거 발화의 일부라 인용 부호로 싼다. 등장인물은 별칭만 쓰고, 아이 · AI 별칭과 사전에 걸리는 별칭은 줄을 내지 않는다.
     * 칸 글이 근거 발화에 들어 있지 않으면(줄과 근거가 맞지 않으면) 줄 · 인용을 내지 않는다.
     */
    private String recallText(String key, Map<String, Object> row, Map<String, Object> utterance) {
        if (key.equals("characters")) {
            String alias = text(row, "alias").strip();
            return alias.isEmpty() || selfAlias(alias) || !dictionary.allowedLiteral(alias) ? "" : " · " + alias;
        }
        String body = ReportQuotes.clean(text(row, "text")).strip();
        if (body.isEmpty() || !normalize(text(utterance, "text")).contains(normalize(body))) {
            return "";
        }
        return " · “" + body + "”";
    }

    /** 6항목: 결과 문장이 있는 활동만 날짜 · 유형 · 저장 결과 문장을 낸다. 인용 대체 없음. */
    ReportRepository.Section conversations(List<ReportInput> inputs) {
        List<String> lines = new ArrayList<>();
        for (var input : chronological(inputs)) {
            if (!input.done()) {
                continue;
            }
            var sentences = transcript(input.result().sentencesJson()).stream().map(s -> text(s, "text").strip()).filter(s -> !s.isEmpty()).toList();
            if (!sentences.isEmpty()) {
                lines.add(DAY.format(input.record().listedAt()) + " · " + typeLabel(input) + " · " + String.join(" ", sentences));
            }
        }
        return section(6, lines, List.of());
    }

    /** 10항목: 연습 활동이 있을 때의 고정 문장 + 정리 상태 · 종료 확인 · 끝난 이유 · 미실시 장면 집계. 줄이 없으면 자료 부족. */
    ReportRepository.Section limitations(List<ReportInput> inputs) {
        List<String> lines = new ArrayList<>();
        if (inputs.stream().anyMatch(i -> "PRACTICE".equals(i.record().activityType()))) {
            lines.add(ReportAssembler.LIMITATION);
        }
        long pending = inputs.stream().filter(i -> i.contentAllowed() && !Set.of("DONE", "FAILED").contains(i.status())).count();
        long failed = inputs.stream().filter(i -> i.contentAllowed() && "FAILED".equals(i.status())).count();
        long unconfirmed = inputs.stream().filter(i -> !i.record().confirmed()).count();
        if (pending > 0) {
            lines.add(PENDING + " " + pending + "개");
        }
        if (failed > 0) {
            lines.add(FAILED + " " + failed + "개");
        }
        if (unconfirmed > 0) {
            lines.add(UNCONFIRMED + " " + unconfirmed + "개");
        }
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (var input : chronological(inputs)) {
            reasons.merge(endReasonText(input), 1, Integer::sum);
        }
        if (!reasons.isEmpty()) {
            lines.add(END_REASONS + " · " + joinCounts(reasons, "회"));
        }
        Map<String, Integer> notDone = new LinkedHashMap<>();
        for (var input : chronological(inputs)) {
            if (!input.done()) {
                continue;
            }
            for (var scene : input.scenes()) {
                if ("NOT_DONE".equals(scene.sceneChip())) {
                    notDone.merge(SceneChipMapper.notDoneText(scene.notDoneReason()), 1, Integer::sum);
                }
            }
        }
        if (!notDone.isEmpty()) {
            lines.add(NOT_DONE_SCENES + " · " + joinCounts(notDone, "개"));
        }
        return section(10, lines, List.of());
    }

    static String endReasonText(ReportInput input) {
        String status = input.record().finalStatus();
        if ("FAILED".equals(status) || "ENDED_UNCONFIRMED".equals(status)) {
            return END_REASON_NO_DEVICE;
        }
        return END_REASON_TEXT.getOrDefault(input.record().endReason() == null ? "" : input.record().endReason(), END_REASON_UNKNOWN);
    }

    private static String joinCounts(Map<String, Integer> counts, String unit) {
        return String.join(" · ", counts.entrySet().stream().map(e -> e.getKey() + " " + e.getValue() + unit).toList());
    }

    private static ReportRepository.Section section(int no, List<String> lines, List<ReportRepository.Citation> citations) {
        if (lines.isEmpty()) {
            return new ReportRepository.Section(no, ReportAssembler.TITLES.get(no - 1), "자료 부족", true, true, List.of());
        }
        return new ReportRepository.Section(no, ReportAssembler.TITLES.get(no - 1), String.join("\n", lines), false, true, List.copyOf(citations));
    }

    private static List<ReportInput> chronological(List<ReportInput> inputs) {
        return inputs.stream().sorted(Comparator.comparing((ReportInput i) -> i.record().listedAt()).thenComparingInt(ReportInput::activityNo)).toList();
    }

    private static String typeLabel(ReportInput input) {
        return "FREE_TALK".equals(input.record().activityType()) ? FREE_TALK : PRACTICE;
    }

    /** 인용할 수 있는 발화: 아이의 확정 전사이고 잡음을 걷어 낸 뒤 내용이 남는다. */
    private static boolean quotable(Map<String, Object> utterance) {
        return "CHILD".equals(utterance.get("speaker")) && !Boolean.TRUE.equals(utterance.get("uncertain")) && number(utterance, "seq") > 0
                && !ReportQuotes.clean(text(utterance, "text")).isEmpty();
    }

    private static Map<Long, Map<String, Object>> childUtterances(List<Map<String, Object>> transcript) {
        Map<Long, Map<String, Object>> child = new HashMap<>();
        for (var u : transcript) {
            if (quotable(u)) {
                child.put(number(u, "seq"), u);
            }
        }
        return child;
    }

    /** 구조화 칸 값: 칸 하나(객체) 또는 여러 줄(배열). */
    private static List<Map<String, Object>> rows(Object value) {
        return value instanceof Map<?, ?> ? List.of(object(value)) : objects(value);
    }

    private static String normalize(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
    }

    private static Instant startedAt(Map<String, Object> utterance) {
        try {
            return Instant.parse(text(utterance, "startedAt"));
        } catch (RuntimeException unreadable) {
            // 시각을 읽을 수 없는 발화는 장면 구간에 넣지 않는다.
            return null;
        }
    }

    private static Map<String, String> orderedFieldNames() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("characters", "등장인물");
        names.put("situation", "상황");
        names.put("partnerUtterance", "상대가 한 말");
        names.put("childResponse", "아이의 대답");
        names.put("explanation", "설명");
        return Collections.unmodifiableMap(names);
    }

    private static List<String> fixedPhrases() {
        List<String> phrases = new ArrayList<>(List.of(FREE_TALK, PRACTICE, SCENE, PENDING, FAILED,
                UNCONFIRMED, END_REASONS, NOT_DONE_SCENES, END_REASON_UNKNOWN, END_REASON_NO_DEVICE));
        phrases.addAll(FIELD_NAMES.values());
        phrases.addAll(END_REASON_TEXT.values().stream().sorted().toList());
        return List.copyOf(phrases);
    }
}
