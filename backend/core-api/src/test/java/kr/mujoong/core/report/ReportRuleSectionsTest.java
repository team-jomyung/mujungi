package kr.mujoong.core.report;

import static kr.mujoong.core.report.ReportTestData.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.result.PracticeRecordV2;
import kr.mujoong.core.result.SceneJudgmentRepository;
import kr.mujoong.core.result.check.ForbiddenDictionary;
import org.junit.jupiter.api.*;

/** JIRA-267(FT-1023-c): AI 없이 만드는 2 · 5 · 6 · 10항목 규칙. */
@Tag("result-report")
class ReportRuleSectionsTest {
    static final JsonCodec JSON = JsonCodec.shared();
    final ForbiddenDictionary dictionary = new ForbiddenDictionary();
    final QuoteCandidatePicker picker = new QuoteCandidatePicker();
    final ReportRuleSections sections = new ReportRuleSections(picker, dictionary);
    final UUID goal = UUID.randomUUID();

    /** U5b 형식 연습: 장면 1 FIRST(2) + 도움 뒤(3), 장면 2 NEW_SCENARIO(4). 1 · 5는 장면과 무관한 발화다. */
    ReportInput legacyPractice(int no) {
        Instant t = NOW.minusSeconds(600);
        var transcript = List.of(
                utterance(1, "CHILD", "공룡 좋아", t),
                utterance(2, "CHILD", "블록 하나 빌려 줄래", t.plusSeconds(10)),
                utterance(3, "CHILD", "같이 쓰자 블록 놀이", t.plusSeconds(20)),
                utterance(4, "CHILD", "연필 좀 줄 수 있어", t.plusSeconds(30)),
                utterance(5, "CHILD", "이제 그만 놀래 정말", t.plusSeconds(40)));
        String s1 = UUID.randomUUID().toString(), s2 = UUID.randomUUID().toString();
        var practice = Map.of("goals", List.of(Map.of("goalSelectionId", goal.toString(), "behaviorKey", "ask", "behaviorVersion", 1,
                "displayName", "부탁하기", "cues", List.of(), "responses", List.of(
                        Map.of("responseType", "FIRST", "utteranceSeq", 2, "scenarioId", s1),
                        Map.of("responseType", "AFTER_CUE", "utteranceSeq", 3, "scenarioId", s1),
                        Map.of("responseType", "NEW_SCENARIO", "utteranceSeq", 4, "scenarioId", s2)))));
        var base = withRecord(practice(no, "DONE", "FULL", goal, List.of()), null, null, null, JSON.write(transcript), JSON.write(practice));
        return withScenes(base, List.of(
                new SceneJudgmentRepository.Scene(goal, 1, 1, "ORIGINAL", null, "functional", "IMMEDIATE", null, null, false, null, "DONE_IMMEDIATE"),
                new SceneJudgmentRepository.Scene(goal, 1, 2, "NEW_SCENARIO", null, "partial", null, null, null, false, null, "NEXT_TIME")));
    }

    @Test
    void observedScenesQuoteLegacyPracticeSceneUtterancesInDateOrder() {
        var section = sections.write(List.of(legacyPractice(1), input(2, "FREE_TALK", 3, "DONE", "FULL"))).get(2);
        assertThat(section.missing()).isFalse();
        assertThat(section.citations()).extracting(ReportRepository.Citation::utteranceSeq).containsExactly(2L, 3L, 4L);
        assertThat(section.citations()).extracting(ReportRepository.Citation::source).containsOnly("TRANSCRIPT");
        assertThat(section.citations()).extracting(ReportRepository.Citation::quote).containsExactly("블록 하나 빌려 줄래", "같이 쓰자 블록 놀이", "연필 좀 줄 수 있어");
        assertThat(section.text()).contains("부탁하기 · 장면 1 · 바로 전달", "부탁하기 · 장면 2 · 다음 기회로").doesNotContain("자유대화", "functional");
        assertThat(section.text().lines()).hasSize(3);
    }

    @Test
    void observedScenesUseUtterancesInsideModernSceneWindow() {
        Instant t = NOW.minusSeconds(900);
        UUID run1 = UUID.randomUUID(), run2 = UUID.randomUUID();
        var practice = Map.of("flowVersion", PracticeRecordV2.VERSION, "scenes", List.of(
                Map.of("sceneRunId", run1.toString(), "goalRef", goal.toString(), "sceneKind", "BASE", "startedAt", t.toString(), "facts", List.of()),
                Map.of("sceneRunId", run2.toString(), "goalRef", goal.toString(), "sceneKind", "VARIANT", "startedAt", t.plusSeconds(60).toString(), "facts", List.of())));
        var transcript = List.of(
                utterance(1, "CHILD", "준비 전에 한 말 이에요", t.minusSeconds(5)),
                utterance(2, "AI", "가방이 무거워 보여", t.plusSeconds(5)),
                utterance(3, "CHILD", "응", t.plusSeconds(10)),
                utterance(4, "CHILD", "가방 들어 줄 수 있어", t.plusSeconds(20)),
                utterance(5, "CHILD", "고마워 정말 고마워", t.plusSeconds(30)),
                utterance(6, "CHILD", "나도 같이 할래 지금", t.plusSeconds(70)));
        var base = withRecord(practice(1, "DONE", "FULL", goal, List.of()), null, null, null, JSON.write(transcript), JSON.write(practice));
        var input = withScenes(base, List.of(
                new SceneJudgmentRepository.Scene(goal, 1, 1, "ORIGINAL", null, "functional", "AFTER_REASK", null, null, false, null, "DONE_IMMEDIATE", run1, PracticeRecordV2.VERSION, List.of(), null),
                new SceneJudgmentRepository.Scene(goal, 1, 2, "NEW_SCENARIO", null, "uncertain", null, null, null, false, null, "ON_HOLD", run2, PracticeRecordV2.VERSION, List.of(), null)));
        var section = sections.write(List.of(input)).get(2);
        // 장면 1 구간(t ~ t+60)에서 내용 있는 발화 2개(4 · 5), 장면 2 구간에서 6. 장면 밖 1 · AI 발화 2 · 짧은 대답 3은 뺀다.
        assertThat(section.citations()).extracting(ReportRepository.Citation::utteranceSeq).containsExactly(4L, 5L, 6L);
        assertThat(section.text()).contains("전달 · 장면 1 · 되묻기 뒤 전달", "전달 · 장면 2 · 보류");
    }

    @Test
    void observedScenesFallBackToSelectedQuotesWithoutPracticeScenes() {
        var free = List.of(input(1, "FREE_TALK", 3, "DONE", "FULL"), input(2, "FREE_TALK", 2, "DONE", "FULL"));
        var section = sections.write(free).get(2);
        var expected = picker.pick(free).stream().sorted(Comparator.comparing(QuoteCandidatePicker.Quote::at)).map(QuoteCandidatePicker.Quote::citation).toList();
        assertThat(section.citations()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(section.citations()).extracting(ReportRepository.Citation::at).isSorted();
        assertThat(section.text().lines()).allMatch(line -> line.endsWith(" · 자유대화"));
        // 정리 중 연습만 있으면 장면 판정이 없어 선별 인용으로 간다.
        var pendingPractice = sections.write(List.of(legacyPractice(1)).stream().map(i -> new ReportInput(i.activityNo(), i.record(), "RUNNING", i.result(), List.of(), Map.of(), List.of())).toList()).get(2);
        assertThat(pendingPractice.citations()).extracting(ReportRepository.Citation::utteranceSeq).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(pendingPractice.text()).contains("연습").doesNotContain("장면");
    }

    @Test
    void emptySelectionLeavesQuoteSectionsMissingButKeepsLimitation() {
        var written = sections.write(List.of());
        for (int no : List.of(2, 5, 6)) {
            assertThat(written.get(no).missing()).as("항목 " + no).isTrue();
            assertThat(written.get(no).text()).isEqualTo("자료 부족");
        }
        assertThat(written.get(10).missing()).isFalse();
        assertThat(written.get(10).text()).isEqualTo(ReportAssembler.LIMITATION);
    }

    @Test
    void experiencesCopyStoredFieldsAndNeverGuardianText() {
        var structured = new LinkedHashMap<String, Object>();
        structured.put("characters", List.of(Map.of("alias", "친구 A", "source", "CHILD_RECALL", "utteranceSeq", 2),
                Map.of("alias", "자폐 친구", "source", "CHILD_RECALL", "utteranceSeq", 2),
                Map.of("alias", "보호자가 적은 이름", "source", "GUARDIAN_INPUT", "guardianSourceType", "SEEN")));
        structured.put("situation", Map.of("text", "함께 블록", "source", "CHILD_RECALL", "utteranceSeq", 2));
        structured.put("partnerUtterance", null);
        structured.put("explanation", Map.of("text", "보호자 원문 비밀", "source", "GUARDIAN_INPUT", "guardianSourceType", "SEEN"));
        structured.put("childResponse", Map.of("text", "없는 발화", "source", "CHILD_RECALL", "utteranceSeq", 99));
        var section = sections.write(List.of(withStructured(input(1, "FREE_TALK", 3, "DONE", "FULL"), structured))).get(5);
        assertThat(section.text()).contains("등장인물 · 친구 A", "상황 · “함께 블록”", "설명 · 보호자 입력")
                .doesNotContain("보호자 원문 비밀", "보호자가 적은 이름", "자폐", "없는 발화", "아이의 대답");
        assertThat(section.text().lines()).hasSize(5);
        assertThat(section.citations()).filteredOn(c -> c.source().equals("GUARDIAN_INPUT")).hasSize(2)
                .allSatisfy(c -> assertThat(c.quote()).isNull());
        assertThat(section.citations()).filteredOn(c -> c.source().equals("CHILD_RECALL")).hasSize(3)
                .allSatisfy(c -> assertThat(c.utteranceSeq()).isEqualTo(2L))
                .allSatisfy(c -> assertThat(c.quote()).isEqualTo("함께 블록 쌓자"));
        // 정리 중 활동의 구조화는 쓰지 않는다(BR9.8).
        var pending = new ReportInput(1, input(1, "FREE_TALK", 3, "DONE", "FULL").record(), "RUNNING", null, List.of(), structured, List.of());
        assertThat(sections.write(List.of(pending)).get(5).missing()).isTrue();
    }

    @Test
    void conversationsListStoredSentencesOrQuotesPerActivity() {
        var withSentence = withSentences(input(2, "FREE_TALK", 3, "DONE", "FULL"), List.of("10분 동안 이야기를 나눴어요."));
        var pending = new ReportInput(3, input(3, "PRACTICE", 4, "RUNNING", "FULL").record(), "RUNNING", null, List.of(), Map.of(), List.of());
        var withdrawn = withEndReason(withSentences(input(4, "FREE_TALK", 3, "DONE", "FULL"), List.of("철회 비밀 문장")), "CONSENT_WITHDRAWN");
        var section = sections.write(List.of(withSentence, pending, withdrawn)).get(6);
        assertThat(section.text().lines()).containsExactly("10/14 · 연습 · 정리 문장 없음 · 아래 인용", "10/14 · 자유대화 · 10분 동안 이야기를 나눴어요.");
        assertThat(section.text()).doesNotContain("철회 비밀 문장");
        assertThat(section.citations()).hasSize(2).allSatisfy(c -> assertThat(c.sessionId()).isEqualTo(pending.record().sessionId()));
        assertThat(section.citations()).extracting(ReportRepository.Citation::utteranceSeq).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    void limitationsKeepFixedSentenceAndAggregateRecordFacts() {
        var done = withScenes(input(1, "PRACTICE", 2, "DONE", "FULL"), List.of(
                new SceneJudgmentRepository.Scene(goal, 1, 1, "ORIGINAL", null, null, null, null, null, false, "TIME_OVER", "NOT_DONE"),
                new SceneJudgmentRepository.Scene(goal, 1, 2, "NEW_SCENARIO", null, null, null, null, null, false, "ACTIVITY_ENDED", "NOT_DONE"),
                new SceneJudgmentRepository.Scene(goal, 2, 1, "ORIGINAL", null, null, null, null, null, false, "TIME_OVER", "NOT_DONE")));
        var pending = new ReportInput(2, input(2, "FREE_TALK", 1, "RUNNING", "FULL").record(), "RUNNING", null, List.of(), Map.of(), List.of());
        var failed = withRecord(new ReportInput(3, input(3, "FREE_TALK", 1, "FAILED", "FULL").record(), "FAILED", null, List.of(), Map.of(), List.of()), null, "GUARDIAN_STOP", false, null, null);
        var unconfirmed = withRecord(input(4, "FREE_TALK", 1, "DONE", "FULL"), "ENDED_UNCONFIRMED", null, false, null, null);
        var section = sections.write(List.of(done, pending, failed, unconfirmed)).get(10);
        assertThat(section.missing()).isFalse();
        assertThat(section.citations()).isEmpty();
        assertThat(section.text().lines()).containsExactly(
                ReportAssembler.LIMITATION,
                "정리 중인 활동 1개",
                "정리하지 못한 활동 1개",
                "종료가 확인되지 않은 활동 2개",
                "끝난 이유 · 기기 응답 없이 끝냈어요 1회 · 보호자가 끝냈어요 1회 · 아이가 끝냈어요 2회",
                "미실시 장면 · 시간 종료 2개 · 활동 끝 1개");
    }

    @Test
    void generatedTextOutsideQuotesPassesForbiddenDictionary() {
        var structured = Map.<String, Object>of("situation", Map.of("text", "함께 블록", "source", "CHILD_RECALL", "utteranceSeq", 2),
                "explanation", Map.of("text", "원문", "source", "GUARDIAN_INPUT"));
        var inputs = List.of(legacyPractice(1), withStructured(input(2, "FREE_TALK", 3, "DONE", "FULL"), structured),
                withEndReason(input(3, "FREE_TALK", 1, "DONE", "FULL"), "SILENCE"),
                withRecord(input(4, "FREE_TALK", 1, "FAILED", "FULL"), "FAILED", "DISCONNECT", false, null, null));
        for (var section : sections.write(inputs).values()) {
            assertThat(dictionary.allowed(section.text())).as("항목 " + section.sectionNo() + ": " + section.text()).isTrue();
        }
    }
}
