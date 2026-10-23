package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.luna.LunaHosts;
import kr.mujoong.realtime.practiceflow.PracticeDialogueHarnessSelfCheckTest.FakeResponses;
import kr.mujoong.realtime.practiceflow.PracticeDialogueLiveTest.ChildMode;
import kr.mujoong.realtime.practiceflow.PracticeDialogueLiveTest.Endpoint;
import kr.mujoong.realtime.practiceflow.PracticeVoiceDialogueLiveTest.Caps;
import kr.mujoong.realtime.practiceflow.PracticeVoiceDialogueLiveTest.Harness;
import kr.mujoong.realtime.practiceflow.PracticeVoiceDialogueLiveTest.SpeechEndpoint;
import kr.mujoong.realtime.practiceflow.PracticeVoiceDialogueLiveTest.Timing;
import kr.mujoong.realtime.practiceflow.PracticeVoiceDialogueLiveTest.Wiring;
import kr.mujoong.testsupport.provider.FakeProviderWsServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * 실음성 연습 대화 하니스({@link PracticeVoiceDialogueLiveTest})의 배선 점검. 실제 AI를 부르지 않는다(R-F1): 루프백 가짜 GPT-Live(가짜 공급자 WS
 * 서버에 응답기를 붙여 승인 원문 · 생성 문장을 전사 + 음성으로 돌려주고, 아이 음성이 끝나면 입력 전사를 보낸다), 루프백 가짜 TTS(표식이 든 음
 * PCM), 루프백 가짜 Responses API(판정 · AI 아이, {@link FakeResponses})로 대본 아이 1개 · AI 아이 1개 대화를 운영 세션 엔진에서 끝까지 돌리고
 * JSON 결과와 사람이 읽는 대화 기록을 쓰는지 본다. 일부러 승인 원문과 다르게 읽은 구간이 '안 들림 · 폐기'와 사유 코드로 적히는지도 본다.
 *
 * <p>증거 종류는 {@code FAKE_PROVIDER_SELF_CHECK}(테스트 대역 · 합성 대화)이고 키는 가짜 문자열이다(R-F3 · R-M11). 이 시험의 성공은 실제 GPT-Live ·
 * 실제 기기 · 실제 아이 성공이 아니다. 실호출 하니스를 돌리기 전에 배선이 깨지지 않았는지를 기본 시험에서 확인하려는 것이다.
 */
@Tag("practice-flow")
class PracticeVoiceHarnessSelfCheckTest {

    private static final String FAKE_KEY = "test-key-not-real-practice-voice";
    private static final JsonCodec JSON = JsonCodec.shared();
    private static final Path REPO_ROOT = PracticeDialogueLiveTest.repoRoot();
    /**
     * 배선 점검용 하니스 시간(빠르게). 운영 시간 값은 application.yml + application-test.yml에서 읽는다. 기기 승인 음성은 시드 WAV 실제 길이의 5%로 재생한다
     * (실호출은 100%).
     */
    private static final Timing FAST = new Timing(Duration.ofMillis(120), Duration.ofMillis(150), 300.0, Duration.ofMillis(60),
            Duration.ofMillis(10), Duration.ofSeconds(15), Duration.ofSeconds(3), Duration.ofSeconds(15), 0.05);
    /** 기준 대본에서 미리 만든 음성으로 내는 승인 원문 줄(설명 · 첫 대사 · 기회 문장 · 변형 제안 · 변형 기회 문장 · 전환 · 복귀 알림). */
    private static final int STANDARD_SCRIPT_LINES = 7;
    /**
     * test 프로필의 구간 조용 시간(50ms) · 입력 전사 묶음 대기(150ms)는 루프백 조각 사이가 GC · JIT로 잠깐 멈추면 한 말을 둘로 자를 수 있어 배선 점검만
     * 조금 넉넉히 둔다(운영 값 800ms · 1500ms보다 짧다). 실호출 하니스는 덮어쓰지 않는다.
     */
    private static final Map<String, String> SLACK = Map.of("mujoong.gate.segment-quiet", "300ms",
            "mujoong.timing.provider.input-transcript-quiet", "400ms");

    @TempDir
    Path out;

    @Test
    void theHarnessRunsAScriptAndAnAiChildOnTheSessionEngineAgainstLoopbackFakesAndWritesTheHeardTranscript() throws Exception {
        try (FakeResponses luna = FakeResponses.start(); FakeSpeech tts = FakeSpeech.start(); FakeSpeech clips = FakeSpeech.start();
                LoopbackGptLive live = LoopbackGptLive.start(tts)) {
            Harness.Outcome outcome = new Harness(out, wiring(live, luna, tts, clips, EnumSet.of(ChildMode.SCRIPT, ChildMode.AI),
                    new Caps(Duration.ofSeconds(90), 900, 2, 80, 150))).run();

            String md = Files.readString(out.resolve(PracticeVoiceDialogueLiveTest.MARKDOWN_FILE), StandardCharsets.UTF_8);
            String json = Files.readString(out.resolve(PracticeVoiceDialogueLiveTest.OUTPUT_FILE), StandardCharsets.UTF_8);
            assertThat(outcome.ranDialogues()).as(md).isEqualTo(2);
            assertThat(outcome.skipped()).as("P2는 대화 수 상한으로 건너뛴다").isEqualTo(1);
            assertThat(outcome.gptLiveSessions()).isEqualTo(2);
            assertThat(outcome.completed()).as(md).isEqualTo(2);
            assertThat(outcome.evidence()).isEqualTo(PracticeVoiceDialogueLiveTest.SELF_CHECK_EVIDENCE);
            assertThat(json + md).doesNotContain(FAKE_KEY);

            Map<String, Object> summary = map(JSON.read(json, Map.class));
            assertThat(summary).containsEntry("outcome", "COMPLETED").containsEntry("evidence", "FAKE_PROVIDER_SELF_CHECK");
            Map<String, Object> totals = map(summary.get("totals"));
            assertThat(number(totals.get("ttsCalls"))).as("TTS 호출").isEqualTo(tts.requests.size()).isPositive();
            assertThat(number(totals.get("openAiTextCalls"))).as("판정 + AI 아이 호출").isEqualTo(luna.requests.size()).isPositive();
            assertThat(number(totals.get("aiChildCalls"))).isPositive();
            assertThat(((Number) totals.get("gptLiveUsageSecondsReported")).doubleValue()).as("session.usage.updated 합계").isPositive();
            assertThat(tts.bearer).isNotEmpty().allMatch(Boolean::booleanValue);
            assertThat(tts.requests).allSatisfy(request -> assertThat(request).containsEntry("response_format", "pcm")
                    .containsEntry("model", "fake-tts").containsKey("instructions"));
            // 3회차 V1: 대본 줄 음성은 운영 합성기가 운영 설정(gpt-4o-mini-tts · nova · 말투 지시)으로 따로 만든다. 같은 줄은 한 번만 만든다(캐시)
            assertThat(number(totals.get("scriptClipCalls"))).as("대본 줄 음성 합성 호출").isEqualTo(clips.requests.size()).isPositive();
            assertThat(clips.requests).allSatisfy(request -> assertThat(request).containsEntry("response_format", "pcm")
                    .containsEntry("model", "gpt-4o-mini-tts").containsEntry("voice", "nova").containsKey("instructions"));
            assertThat(clips.bearer).allMatch(Boolean::booleanValue);
            assertThat(clips.inputs()).doesNotHaveDuplicates().contains("알았어, 잠깐만!", "자, 여기 초록색!", "잠깐, 무중이 도와줄게",
                    "다시 친구 역할로 돌아갈게");
            assertThat(map(map(summary.get("setup")).get("scriptClips"))).containsEntry("state", "ENABLED");

            List<Map<String, Object>> runs = runs(summary);
            assertThat(runs).extracting(run -> run.get("childMode") + ":" + run.get("child") + ":" + run.get("status"))
                    .containsExactly("SCRIPT:STANDARD:COMPLETED", "AI:P1:COMPLETED", "AI:P2:SKIPPED");
            assertThat(runs.get(2)).containsEntry("skipReason", "DIALOGUE_CAP");
            for (Map<String, Object> run : runs.subList(0, 2)) {
                Map<String, Object> checks = map(run.get("checks"));
                Map<String, Object> verbatim = map(checks.get("approvedVerbatim"));
                assertThat(number(verbatim.get("requested"))).isPositive();
                assertThat(verbatim.get("played")).as("승인 원문 줄은 모두 들렸다").isEqualTo(verbatim.get("requested"));
                Map<String, Object> clipCheck = map(checks.get("scriptLineClips"));
                assertThat(clipCheck.get("clipPlayed")).as("대본 줄은 모두 미리 만든 음성으로 냈다").isEqualTo(verbatim.get("requested"));
                assertThat(clipCheck).containsEntry("fallback", 0).containsEntry("sourceUnknown", 0);
                assertThat(map(checks.get("generatedHeadCut"))).containsEntry("suspected", 0);
                assertThat(turns(run)).filteredOn(turn -> "VERBATIM".equals(turn.get("requested")) || "NOTICE".equals(turn.get("requested")))
                        .isNotEmpty().allSatisfy(turn -> assertThat(turn).containsEntry("source", "SCENARIO_SCRIPT")
                                .containsEntry("lineSource", "TTS_CLIP").containsKey("clipMs"));
                List<Map<String, Object>> timeline = ((List<?>) run.get("instructionTimeline")).stream()
                        .map(PracticeVoiceHarnessSelfCheckTest::map).toList();
                assertThat(timeline).as("3회차 V2: 지시마다 새 말 시작 관측").isNotEmpty()
                        .noneMatch(row -> "VERBATIM_READ".equals(row.get("instruction")))
                        .filteredOn(row -> String.valueOf(row.get("instruction")).startsWith("GENERATED:")).isNotEmpty()
                        .allSatisfy(row -> assertThat(row).containsEntry("observed", true).containsKey("start").containsKey("sentClockMs")
                                .containsKey("ackEndMs").containsKey("firstTranscriptStartMs").containsKey("discardedTranscriptChars")
                                .containsEntry("outcome", "RECEIVING"));
                assertThat(map(map(checks.get("unheardSegments")).get("byKind"))).isEmpty();
                assertThat(map(checks.get("childTranscriptDiffers"))).containsEntry("differs", 0);
                assertThat(number(map(checks.get("childTranscriptDiffers")).get("spoken"))).isGreaterThanOrEqualTo(3);
                assertThat(map(run.get("gptLive"))).containsEntry("sessionStarted", true);
                assertThat(number(map(run.get("gptLive")).get("voicedInputFramesOffered"))).as("아이 음성이 20ms 프레임으로 들어갔다").isPositive();
            }
            Map<String, Object> scriptChecks = map(runs.getFirst().get("checks"));
            Map<String, Object> standard = map(scriptChecks.get("standardJudgmentMatches"));
            assertThat(standard.get("matched")).as("기준 대화 기대 판정").isEqualTo(standard.get("judged"));
            assertThat(number(standard.get("judged"))).isEqualTo(5);
            assertThat(map(scriptChecks.get("scriptLineClips"))).as("기준 대화 16차례: 대본 줄 7 · 생성형 4 · 판정 5")
                    .containsEntry("clipPlayed", STANDARD_SCRIPT_LINES).containsEntry("requested", STANDARD_SCRIPT_LINES);
            assertThat(map(scriptChecks.get("generatedHeard"))).containsEntry("played", 4).containsEntry("requested", 4);
            assertThat(live.spokenVerbatim).as("GPT-Live는 대본 줄을 읽지 않았다").isEmpty();
            Map<String, Object> approvedAudio = map(map(map(summary.get("setup")).get("approvedAudioMs")).get("byCode"));
            assertThat(approvedAudio).as("기기 승인 음성 길이는 시드 WAV에서").containsEntry("CLOSING", 2000).containsEntry("INTRO_PRACTICE", 4000);

            // 사람이 읽는 대화 기록: 들린 승인 원문 · 생성 · 기기 승인 음성 · 아이 의도와 들은 전사 · 판정 · 다음 말까지 걸린 시간
            assertThat(md).contains("# 연습 음성 대화 기록 (GPT-Live 실음성 · 합성 아이 음성)", "FAKE_PROVIDER_SELF_CHECK", "### 대본 아이 · STANDARD",
                    "### AI가 연기하는 아이 · P1", "- 상태: SKIPPED(상한: DIALOGUE_CAP) — 시작하지 않았다", "점검표", "(기기 승인 음성 INTRO_PRACTICE)",
                    "(승인 원문 그대로 · ", " · 미리 만든 음성(TTS_CLIP", "(생성 · ", " · GPT-Live", "→ 들은 전사 «어… 이거 아닌데.» (J2 PARTIAL 0.90",
                    "기대 J2 PARTIAL", "다음 말까지 ", "- 안 들린 GPT-Live 구간: 없음", "- 기준 대화 기대 판정과 같음: 5 / 5",
                    "- 대본 줄 TTS 재생: " + STANDARD_SCRIPT_LINES + " / " + STANDARD_SCRIPT_LINES, "- 대체(GPT-Live 원문 읽기): 0",
                    "- 생성형 줄 첫머리 잘림 의심(낱말 중간 시작): 0 / 4", "지시별 새 말 시작(3회차 V2", "| GENERATED:",
                    " · 대본 줄 음성 합성 " + clips.requests.size() + "회(상한으로 막힘 0)", " · 대본 줄 음성 합성 120회\n")
                    .as("폐기된 구간 줄이 없다").doesNotContainPattern("\\] \\(안 들림\\) ");
            String standardRun = md.substring(md.indexOf("### 대본 아이 · STANDARD"), md.indexOf("### AI가 연기하는 아이 · P1"));
            assertInOrder(standardRun, "아이: 응, 할래. → 들은 전사 «응, 할래.»", "무중: 무중이랑 같이 공룡 그림 그린다고 생각해 보자.",
                    "친구: 알았어, 잠깐만!", "친구: 자, 여기 초록색!", "아이: 어… 이거 아닌데.", "친구: " + LoopbackGptLive.GENERATED_LINE,
                    "아이: 파란색! 나 파란색 달라고 했어.", "아이: 응. → 들은 전사 «응.»", "아이: 음… 몰라. 뭐라고 해?",
                    "아이: 아니, 나 딸기 우유 달라고 했어.", "(기기 승인 음성 CLOSING)");

            // 가짜 GPT-Live가 받은 것: 생성 지시(들린 대본 줄을 데이터로 실음) · 아이 음성 프레임. 대본 줄 읽기 지시는 없다(3회차 V1). 입력 전사는 TTS에
            // 보낸 글 그대로 돌려줬다
            assertThat(live.generated.get()).isPositive();
            assertThat(live.instructions).noneMatch(content -> content.startsWith(LoopbackGptLive.VERBATIM_LEAD))
                    .anyMatch(content -> content.startsWith(kr.mujoong.realtime.voice.PracticeInstruction.HEARD_LEAD)
                            && content.contains("자, 여기 초록색!"));
            assertThat(live.inputTranscripts).contains("응, 할래.", "어… 이거 아닌데.");
            assertThat(live.server.bearerHeaders()).isNotEmpty().allMatch(Boolean::booleanValue);
            assertThat(String.join("\n", live.server.rawReceived())).doesNotContain(FAKE_KEY);

            // AI 아이는 아이가 실제로 들은 말만 받는다: 지시문 · 승인 데이터 묶음 · 대본 칸 이름은 가지 않는다
            List<Map<String, Object>> childRequests = luna.requests.stream()
                    .filter(request -> String.valueOf(request.get("instructions")).contains("합성 인물 '하늘이'")).toList();
            assertThat(childRequests).isNotEmpty().allSatisfy(request -> assertThat(JSON.write(request.get("input"))).doesNotContain(
                    "다음 JSON은", "partnerRole", "이번 한 번만", "focusPoints", "expressionExamples", "judgmentCriteria"));
        }
    }

    @Test
    void aDeliberatelyDifferentReadingOfAnApprovedLineIsRecordedAsUnheardWithItsReasonAndTheTranscriptDifferenceIsKept() throws Exception {
        try (FakeResponses luna = FakeResponses.start(); FakeSpeech tts = FakeSpeech.start(); LoopbackGptLive live = LoopbackGptLive.start(tts)) {
            live.mismatchVerbatim(1).hearing(text -> "응, 할래.".equals(text) ? "응 할게" : text);

            // 대본 줄 음성을 끈 배선(3회차 V1 전과 같다): 모든 대본 줄을 GPT-Live가 원문 읽기로 읽어야 일부러 다르게 읽을 수 있다
            Harness.Outcome outcome = new Harness(out, wiring(live, luna, tts, null, EnumSet.of(ChildMode.SCRIPT),
                    new Caps(Duration.ofSeconds(60), 900, 1, 40, 60))).run();

            String md = Files.readString(out.resolve(PracticeVoiceDialogueLiveTest.MARKDOWN_FILE), StandardCharsets.UTF_8);
            assertThat(outcome.ranDialogues()).as(md).isEqualTo(1);
            assertThat(live.mismatched).as(md).hasSize(1);
            String approved = live.mismatched.getFirst();
            Map<String, Object> run = runs(readJson(out.resolve(PracticeVoiceDialogueLiveTest.OUTPUT_FILE))).getFirst();
            assertThat(run.get("status")).as(md).isNotEqualTo("ERROR");

            List<Map<String, Object>> discarded = turns(run).stream().filter(turn -> Boolean.FALSE.equals(turn.get("heard"))).toList();
            assertThat(discarded).as(md).singleElement().satisfies(turn -> {
                assertThat(turn).containsEntry("discard", "APPROVED_TEXT_MISMATCH").containsEntry("gateReason", "GATE_PATTERN")
                        .containsEntry("approvedText", approved).containsEntry("text", approved + LoopbackGptLive.MISMATCH_TAIL)
                        .containsEntry("requested", "VERBATIM");
            });
            Map<String, Object> checks = map(run.get("checks"));
            assertThat(map(map(checks.get("unheardSegments")).get("byKind"))).containsExactly(Map.entry("APPROVED_TEXT_MISMATCH", 1));
            Map<String, Object> verbatim = map(checks.get("approvedVerbatim"));
            assertThat(number(verbatim.get("played"))).isEqualTo(number(verbatim.get("requested")) - 1);
            assertThat((List<?>) verbatim.get("notHeard")).singleElement().satisfies(item -> assertThat(map(item))
                    .containsEntry("text", approved).containsEntry("outcome", "FAILED"));
            assertThat(map(checks.get("childTranscriptDiffers"))).containsEntry("differs", 1);
            Map<String, Object> clipCheck = map(checks.get("scriptLineClips"));
            assertThat(clipCheck).as("꺼진 장치: 모든 대본 줄이 GPT-Live 원문 읽기 대체(DISABLED)").containsEntry("clipPlayed", 0)
                    .containsEntry("fallback", number(verbatim.get("requested")));
            assertThat(map(clipCheck.get("fallbackReasons"))).containsOnlyKeys("DISABLED");
            assertThat(((List<?>) run.get("instructionTimeline")).stream().map(PracticeVoiceHarnessSelfCheckTest::map))
                    .anySatisfy(row -> assertThat(row).containsEntry("instruction", "VERBATIM_READ").containsEntry("observed", true));

            assertThat(md).contains("(안 들림) ", approved + LoopbackGptLive.MISMATCH_TAIL + " — 폐기: 승인 원문과 다름(APPROVED_TEXT_MISMATCH) · 원문 «"
                    + approved + "»", "- 안 들린 GPT-Live 구간: {APPROVED_TEXT_MISMATCH=1}", "→ 들은 전사 «응 할게»(의도와 다름)",
                    "- 아이 전사가 의도와 다름: 1 / ", "- 대체(GPT-Live 원문 읽기): 1(사유 {DISABLED=1})", "| VERBATIM_READ |");
        }
    }

    @Test
    void aScriptLineWhoseVoiceFailsFallsBackToTheGptLiveReadingAndIsMarkedWithItsReason() throws Exception {
        try (FakeResponses luna = FakeResponses.start(); FakeSpeech tts = FakeSpeech.start(); FakeSpeech clips = FakeSpeech.start();
                LoopbackGptLive live = LoopbackGptLive.start(tts)) {
            clips.failOn("자, 여기 초록색!", 500);

            Harness.Outcome outcome = new Harness(out, wiring(live, luna, tts, clips, EnumSet.of(ChildMode.SCRIPT),
                    new Caps(Duration.ofSeconds(60), 900, 1, 40, 60))).run();

            String md = Files.readString(out.resolve(PracticeVoiceDialogueLiveTest.MARKDOWN_FILE), StandardCharsets.UTF_8);
            assertThat(outcome.completed()).as(md).isEqualTo(1);
            assertThat(live.spokenVerbatim).as("합성에 실패한 그 줄만 GPT-Live가 읽었다").containsExactly("자, 여기 초록색!");
            Map<String, Object> run = runs(readJson(out.resolve(PracticeVoiceDialogueLiveTest.OUTPUT_FILE))).getFirst();
            Map<String, Object> clipCheck = map(map(run.get("checks")).get("scriptLineClips"));
            assertThat(clipCheck).containsEntry("clipPlayed", STANDARD_SCRIPT_LINES - 1).containsEntry("requested", STANDARD_SCRIPT_LINES)
                    .containsEntry("fallback", 1);
            assertThat(map(clipCheck.get("fallbackReasons"))).containsExactly(Map.entry("SYNTHESIS_PROVIDER_ERROR", 1));
            assertThat(turns(run)).filteredOn(turn -> "자, 여기 초록색!".equals(turn.get("text"))).singleElement()
                    .satisfies(turn -> assertThat(turn).containsEntry("source", "GPT_LIVE").containsEntry("lineSource", "GPT_LIVE_FALLBACK")
                            .containsEntry("sourceReason", "SYNTHESIS_PROVIDER_ERROR"));
            assertThat(md).contains("- 대본 줄 TTS 재생: " + (STANDARD_SCRIPT_LINES - 1) + " / " + STANDARD_SCRIPT_LINES,
                    "- 대체(GPT-Live 원문 읽기): 1(사유 {SYNTHESIS_PROVIDER_ERROR=1})",
                    "친구: 자, 여기 초록색! (승인 원문 그대로 · ", "GPT-Live 원문 읽기(대체 GPT_LIVE_FALLBACK: SYNTHESIS_PROVIDER_ERROR)");
        }
    }

    @Test
    void theSeedWavLengthsAreReadFromTheirRiffHeaders() throws IOException {
        byte[] closing = Files.readAllBytes(REPO_ROOT.resolve("infra/seed-audio/closing-v1.wav"));

        assertThat(PracticeVoiceDialogueLiveTest.wavDurationMs(closing)).isEqualTo(2000);
        assertThat(PracticeVoiceDialogueLiveTest.wavDurationMs(new byte[] {1, 2, 3})).isEqualTo(-1);
        assertThat(PracticeVoiceDialogueLiveTest.approvedAudioMs(REPO_ROOT, PracticeVoiceDialogueLiveTest.approvedContent(REPO_ROOT)))
                .containsEntry("CLOSING", 2000L).containsEntry("WAITING_FILLER", 1500L).containsEntry("INTRO_PRACTICE", 4000L);
    }

    @Test
    void aCallCapReachedMidDialogueEndsItTheGuardianStopWayWithoutCallingAndTheRemainingDialoguesAreSkipped() throws Exception {
        try (FakeResponses luna = FakeResponses.start(); FakeSpeech tts = FakeSpeech.start(); FakeSpeech clips = FakeSpeech.start();
                LoopbackGptLive live = LoopbackGptLive.start(tts)) {
            Harness.Outcome outcome = new Harness(out, wiring(live, luna, tts, clips, EnumSet.of(ChildMode.SCRIPT, ChildMode.AI),
                    new Caps(Duration.ofSeconds(60), 900, 3, 80, 2))).run();

            String md = Files.readString(out.resolve(PracticeVoiceDialogueLiveTest.MARKDOWN_FILE), StandardCharsets.UTF_8);
            assertThat(outcome.ranDialogues()).as(md).isEqualTo(1);
            assertThat(outcome.skipped()).isEqualTo(2);
            assertThat(luna.requests).as("판정 + AI 아이 호출은 상한 2회를 넘지 않는다").hasSize(2);
            List<Map<String, Object>> runs = runs(readJson(out.resolve(PracticeVoiceDialogueLiveTest.OUTPUT_FILE)));
            assertThat(runs).extracting(run -> run.get("child") + ":" + run.get("status") + ":" + run.get("skipReason"))
                    .containsExactly("STANDARD:TEXT_CALL_CAP:null", "P1:SKIPPED:TEXT_CALL_CAP", "P2:SKIPPED:TEXT_CALL_CAP");
            assertThat(map(runs.getFirst().get("session"))).containsEntry("finalStatus", "ENDED").containsEntry("endReason", "GUARDIAN_STOP");
            assertThat(turns(runs.getFirst())).as(md).anySatisfy(turn -> assertThat(turn.get("judgment")).isInstanceOf(Map.class)
                    .satisfies(judgment -> assertThat(map(judgment)).containsEntry("notCalledByCap", true)));
            assertThat(md).contains("*하니스가 끝내기를 요청함(TEXT_CALL_CAP)*", "호출 상한으로 부르지 않음", "(기기 승인 음성 CLOSING)",
                    "- 상태: SKIPPED(상한: TEXT_CALL_CAP) — 시작하지 않았다");
        }
    }

    @Test
    void withoutAKeyOnlySkippedIsWrittenAndAPreviousRealResultMovesAside() throws IOException {
        Files.writeString(out.resolve(PracticeVoiceDialogueLiveTest.OUTPUT_FILE), "{\"outcome\":\"COMPLETED\"}", StandardCharsets.UTF_8);

        PracticeVoiceDialogueLiveTest.writeSkipped(out);

        assertThat(Files.readString(out.resolve(PracticeVoiceDialogueLiveTest.OUTPUT_FILE), StandardCharsets.UTF_8))
                .contains("SKIPPED_NO_KEY").doesNotContain("COMPLETED");
        assertThat(Files.readString(out.resolve(PracticeVoiceDialogueLiveTest.MARKDOWN_FILE), StandardCharsets.UTF_8))
                .contains("SKIPPED_NO_KEY", "통과 아님");
        assertThat(Files.readString(out.resolve(PracticeDialogueLiveTest.PREVIOUS_DIR).resolve(PracticeVoiceDialogueLiveTest.OUTPUT_FILE),
                StandardCharsets.UTF_8)).contains("COMPLETED");

        // 다시 건너뛰어도 옮겨 둔 실호출 결과를 SKIPPED로 덮지 않는다
        PracticeVoiceDialogueLiveTest.writeSkipped(out);
        assertThat(Files.readString(out.resolve(PracticeDialogueLiveTest.PREVIOUS_DIR).resolve(PracticeVoiceDialogueLiveTest.OUTPUT_FILE),
                StandardCharsets.UTF_8)).contains("COMPLETED");
    }

    @Test
    void capsAndChildModesComeFromEnvWithConservativeDefaultsAndBadValuesAreRefusedBeforeAnyCall() {
        Caps defaults = Caps.fromEnv(name -> null);
        assertThat(defaults).isEqualTo(new Caps(Duration.ofSeconds(300), 1800, 8, 120, 200));
        assertThat(defaults.clipCalls()).as("3회차 V1: 대본 줄 음성 합성 상한 기본값").isEqualTo(PracticeVoiceDialogueLiveTest.DEFAULT_CLIP_CALLS);

        Caps given = Caps.fromEnv(Map.of(PracticeVoiceDialogueLiveTest.DIALOGUE_SECONDS_ENV, " 120 ", PracticeVoiceDialogueLiveTest.TOTAL_SECONDS_ENV,
                "600", PracticeVoiceDialogueLiveTest.MAX_DIALOGUES_ENV, "2", PracticeVoiceDialogueLiveTest.TTS_CALLS_ENV, "30",
                PracticeVoiceDialogueLiveTest.TEXT_CALLS_ENV, "40", PracticeVoiceDialogueLiveTest.CLIP_CALLS_ENV, "16")::get);
        assertThat(given).isEqualTo(new Caps(Duration.ofSeconds(120), 600, 2, 30, 40, 16));
        assertThatThrownBy(() -> Caps.fromEnv(Map.of(PracticeVoiceDialogueLiveTest.CLIP_CALLS_ENV, "-1")::get))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(PracticeVoiceDialogueLiveTest.CLIP_CALLS_ENV);
        assertThatThrownBy(() -> Caps.fromEnv(Map.of(PracticeVoiceDialogueLiveTest.MAX_DIALOGUES_ENV, "0")::get))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(PracticeVoiceDialogueLiveTest.MAX_DIALOGUES_ENV);
        assertThatThrownBy(() -> Caps.fromEnv(Map.of(PracticeVoiceDialogueLiveTest.TTS_CALLS_ENV, "many")::get))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(PracticeVoiceDialogueLiveTest.parseChildModes(null)).containsExactlyInAnyOrder(ChildMode.SCRIPT, ChildMode.AI);
        assertThat(PracticeVoiceDialogueLiveTest.parseChildModes(" ai ")).containsExactly(ChildMode.AI);
        assertThat(PracticeVoiceDialogueLiveTest.parseChildModes("RULE")).as("규칙 문장 아이는 실음성 하니스에서 돌지 않는다")
                .containsExactlyInAnyOrder(ChildMode.SCRIPT, ChildMode.AI);
        assertThat(PracticeVoiceDialogueLiveTest.sendsInstructions("gpt-4o-mini-tts")).isTrue();
        assertThat(PracticeVoiceDialogueLiveTest.sendsInstructions("tts-1")).isFalse();
    }

    @Test
    void theLiveWiringUsesTheDemoHostRuleStrictAddressesAndTheProductionTimingFile() throws IOException {
        Wiring wiring = Wiring.live(Map.<String, String>of()::get);

        assertThat(wiring.evidence()).isEqualTo(PracticeVoiceDialogueLiveTest.EVIDENCE);
        assertThat(wiring.providerProfile()).isEqualTo("demo");
        assertThat(kr.mujoong.realtime.voice.ProviderHosts.check(wiring.providerUrl(), "demo")).isEmpty();
        assertThat(wiring.luna().mode()).isEqualTo(LunaHosts.Mode.STRICT);
        assertThat(wiring.speech().mode()).isEqualTo(LunaHosts.Mode.STRICT);
        assertThat(wiring.speech().model()).isEqualTo(PracticeVoiceDialogueLiveTest.DEFAULT_TTS_MODEL);
        assertThat(wiring.speech().voice()).isEqualTo(PracticeVoiceDialogueLiveTest.DEFAULT_TTS_VOICE);
        assertThat(wiring.propertyResources()).containsExactly("application.yml");
        assertThat(wiring.propertyOverrides()).as("실호출은 운영 시간 값 그대로").isEmpty();
        assertThat(wiring.toString() + wiring.speech() + wiring.clips()).doesNotContain(wiring.providerUrl().getHost());
        assertThat(wiring.clips()).as("3회차 V1: 대본 줄 음성은 운영 설정 파일 주소(STRICT)로 운영 합성기를 쓴다").isNotNull();
        assertThat(wiring.clips().baseUrl()).isNull();
        assertThat(wiring.clips().mode()).isEqualTo(LunaHosts.Mode.STRICT);
        URI production = PracticeVoiceDialogueLiveTest.bindRealtime(List.of("application.yml"), Map.of()).speechClip().baseUrl();
        assertThat(LunaHosts.check(kr.mujoong.realtime.speech.OpenAiSpeechClipSynthesizer.endpoint(production), LunaHosts.Mode.STRICT)).isEmpty();
        assertThat(wiring.timing().approvedAudioScale()).as("실호출은 승인 음성 실제 길이 그대로").isEqualTo(1.0);
    }

    @Test
    void buildRulesKeepTheVoiceHarnessOutOfDefaultTestAndCheck() throws IOException {
        String conventions = read("backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts");
        assertThat(conventions).contains("val gptLivePracticeTag = \"gpt-live-practice\"", "excludeTags(gptLivePracticeTag)",
                "inputs.property(\"fixedExcludeTagsGptLivePractice\", gptLivePracticeTag)");

        String realtime = read("backend/realtime-activity/build.gradle.kts");
        assertThat(realtime).contains("tasks.register<Test>(\"gptLivePracticeTest\")", "setIncludeTags(setOf(gptLivePracticeTag))");
        assertThat(realtime).as("check · build가 gptLivePracticeTest에 의존하지 않는다").doesNotContain("dependsOn(\"gptLivePracticeTest\")",
                "dependsOn(tasks.named(\"gptLivePracticeTest\"))", "finalizedBy(\"gptLivePracticeTest\")");

        String root = read("backend/build.gradle.kts");
        assertThat(root).contains("tasks.register(\"verifyGptLivePracticeIsolation\")", "dependsOn(verifyGptLivePracticeIsolation)",
                "it.name == \"gptLivePracticeTest\"", "gptLivePracticeTag !in testOptions.excludeTags",
                "liveOptions.includeTags != setOf(gptLivePracticeTag)");
        assertThat(PracticeVoiceDialogueLiveTest.class.getAnnotation(Tag.class).value()).isEqualTo("gpt-live-practice");
    }

    // ------------------------------------------------------------------ 도우미

    /** @param clips 대본 줄 음성 합성 가짜(null이면 대본 줄 음성을 쓰지 않는 배선 — 모든 대본 줄이 GPT-Live 원문 읽기) */
    private static Wiring wiring(LoopbackGptLive live, FakeResponses luna, FakeSpeech tts, FakeSpeech clips, Set<ChildMode> modes, Caps caps) {
        Endpoint judge = new Endpoint(luna.baseUrl(), LunaHosts.Mode.TEST, () -> FAKE_KEY, PracticeVoiceDialogueLiveTest.SELF_CHECK_EVIDENCE);
        SpeechEndpoint speech = new SpeechEndpoint(tts.baseUrl(), LunaHosts.Mode.TEST, () -> FAKE_KEY, "fake-tts", "fake-voice");
        PracticeVoiceDialogueLiveTest.ClipEndpoint clip = clips == null ? null
                : new PracticeVoiceDialogueLiveTest.ClipEndpoint(clips.baseUrl(), LunaHosts.Mode.TEST, () -> FAKE_KEY);
        return new Wiring(PracticeVoiceDialogueLiveTest.SELF_CHECK_EVIDENCE, live.uri(), "test", () -> FAKE_KEY, judge, speech, clip,
                List.of("application.yml", "application-test.yml"), SLACK, modes, caps, FAST, REPO_ROOT);
    }

    private static String read(String relative) throws IOException {
        return Files.readString(REPO_ROOT.resolve(relative), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> readJson(Path file) throws IOException {
        return map(JSON.read(Files.readString(file, StandardCharsets.UTF_8), Map.class));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private static int number(Object value) {
        assertThat(value).isInstanceOf(Number.class);
        return ((Number) value).intValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> runs(Map<String, Object> summary) {
        List<Map<String, Object>> runs = new ArrayList<>();
        for (Object goal : (List<Object>) summary.get("goals")) {
            Object rows = map(goal).get("runs");
            if (rows instanceof List<?> list) {
                list.forEach(row -> runs.add(map(row)));
            }
        }
        return runs;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> turns(Map<String, Object> run) {
        return ((List<Object>) run.get("turns")).stream().map(PracticeVoiceHarnessSelfCheckTest::map).toList();
    }

    private static void assertInOrder(String text, String... parts) {
        int from = 0;
        for (String part : parts) {
            int at = text.indexOf(part, from);
            assertThat(at).as("순서대로 '%s'\n%s", part, text).isGreaterThanOrEqualTo(0);
            from = at + part.length();
        }
    }

    /** 표식이 든 음 PCM(24kHz 16비트 모노, 20ms 프레임 단위). 첫 두 표본에 표식과 번호를 넣어 가짜 GPT-Live가 어느 글의 음성인지 안다. */
    static byte[] speechPcm(int id, int frames) {
        byte[] pcm = tone(frames);
        pcm[0] = LoopbackGptLive.MARK;
        pcm[1] = LoopbackGptLive.MARK;
        pcm[2] = (byte) id;
        pcm[3] = (byte) (id >>> 8);
        return pcm;
    }

    /** 들리는 음(무음이 아닌 PCM). 공급자 대역의 출력 음성 · 가짜 TTS 음성에 쓴다. */
    static byte[] tone(int frames) {
        byte[] pcm = new byte[frames * PracticeVoiceDialogueLiveTest.FRAME_BYTES];
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short sample = (short) (3000 * Math.sin(2 * Math.PI * (i / 2) / 48.0));
            pcm[i] = (byte) sample;
            pcm[i + 1] = (byte) (sample >>> 8);
        }
        return pcm;
    }

    /**
     * 루프백 가짜 GPT-Live. 가짜 공급자 WS 서버(지시 확인 사건 · 낡은 꼬리 흉내 채움 · 오디오 시계는 서버가 한다)에 응답기를 붙인다: 승인 원문 지시
     * ({@code PracticeInstruction.forSpeech} 모양)에는 그 speech를, 그 밖의 지시(생성형 역할 발화 등)에는 정해진 한 문장을 전사 + 들리는 음으로
     * 돌려준다. 받은 입력 음성 프레임에서 아이 음성이 끝나면(무음 프레임 {@value #END_SILENCE_FRAMES}개) 그 음성의 글을 입력 전사 조각 둘로 보낸다.
     * 말할 때마다 사용량 사건을 보낸다. 실제 공급자가 아니다.
     */
    static final class LoopbackGptLive implements AutoCloseable {

        static final String GENERATED_LINE = "응? 그렇구나, 알겠어.";
        /** 일부러 다르게 읽을 때 승인 원문 뒤에 붙이는 말(실측에서 본 덧붙임 모양). */
        static final String MISMATCH_TAIL = " 정말 잘했어!";
        static final byte MARK = 0x7A;
        static final String VERBATIM_LEAD = PracticeVoiceDialogueLiveTest.VERBATIM_LEAD;
        private static final int END_SILENCE_FRAMES = 6;
        private static final Duration RESPONSE_DELAY = Duration.ofMillis(30);
        private static final Duration TRANSCRIPT_GAP = Duration.ofMillis(30);

        final FakeProviderWsServer server;
        /** 받은 지시 본문(순서대로). */
        final List<String> instructions = new CopyOnWriteArrayList<>();
        final List<String> spokenVerbatim = new CopyOnWriteArrayList<>();
        final List<String> mismatched = new CopyOnWriteArrayList<>();
        final List<String> inputTranscripts = new CopyOnWriteArrayList<>();
        final AtomicInteger generated = new AtomicInteger();
        private final FakeSpeech speech;
        private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("loopback-gpt-live").factory());
        private final AtomicLong appends = new AtomicLong();
        private volatile long sessionStartNanos = System.nanoTime();
        private volatile int mismatchAt = -1;
        private volatile Function<String, String> hearing = Function.identity();
        // 서버 수신 스레드에서만 쓴다
        private int verbatimInSession;
        private int voicedFrames;
        private int silentAfterVoice;
        private int heardId = -1;

        private LoopbackGptLive(FakeSpeech speech) {
            this.speech = speech;
            this.server = FakeProviderWsServer.start();
            server.onReceive(this::onReceive);
        }

        static LoopbackGptLive start(FakeSpeech speech) {
            return new LoopbackGptLive(speech);
        }

        URI uri() {
            return server.uri();
        }

        /** 세션마다 {@code nth}번째(1부터) 승인 원문 지시를 원문과 다르게 읽는다. */
        LoopbackGptLive mismatchVerbatim(int nth) {
            this.mismatchAt = nth;
            return this;
        }

        /** 아이 음성의 글을 입력 전사로 바꾸는 규칙(기본: 그대로). */
        LoopbackGptLive hearing(Function<String, String> rule) {
            this.hearing = rule;
            return this;
        }

        private void onReceive(JsonNode node) {
            String type = text(node, "type");
            if ("session.start".equals(type)) {
                // 세션(연결)마다 오디오 시계 · 사용량 · 승인 원문 순번을 처음부터 센다(실제 공급자처럼)
                server.resetAudioClock();
                sessionStartNanos = System.nanoTime();
                verbatimInSession = 0;
                voicedFrames = 0;
                silentAfterVoice = 0;
            } else if ("session.instructions.append".equals(type)) {
                String content = text(node, "content");
                instructions.add(content == null ? "" : content);
                String line = respond(content);
                long seq = appends.incrementAndGet();
                worker.execute(() -> say(seq, line));
            } else if ("session.input_audio.append".equals(type)) {
                String audio = text(node, "audio");
                onInputFrame(audio == null ? new byte[0] : Base64.getDecoder().decode(audio));
            }
        }

        /** 지시 하나에 할 말(승인 원문이면 speech, 그 밖은 정해진 한 문장). */
        @SuppressWarnings("unchecked")
        private String respond(String content) {
            if (content != null && content.startsWith(VERBATIM_LEAD)) {
                Map<String, Object> data = JSON.read(content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1), Map.class);
                String approved = String.valueOf(data.get("speech"));
                if (++verbatimInSession == mismatchAt) {
                    mismatched.add(approved);
                    return approved + MISMATCH_TAIL;
                }
                spokenVerbatim.add(approved);
                return approved;
            }
            generated.incrementAndGet();
            return GENERATED_LINE;
        }

        /** 실제 모델처럼 확인 사건 뒤 잠깐 있다가 말한다. 그사이 새 지시가 오면 낡은 지시는 말하지 않는다. */
        private void say(long seq, String line) {
            sleep(RESPONSE_DELAY);
            if (seq != appends.get()) {
                return;
            }
            try {
                int frames = Math.max(3, Math.min(15, line.length() / 2));
                List<byte[]> chunks = new ArrayList<>();
                byte[] pcm = tone(frames);
                for (int offset = 0; offset < pcm.length; offset += PracticeVoiceDialogueLiveTest.FRAME_BYTES) {
                    chunks.add(java.util.Arrays.copyOfRange(pcm, offset, offset + PracticeVoiceDialogueLiveTest.FRAME_BYTES));
                }
                server.emitSpeech(line, chunks);
                server.emitUsage((System.nanoTime() - sessionStartNanos) / 1e9);
            } catch (IllegalStateException closed) {
                // 연결이 닫혔다(세션이 끝났다)
            }
        }

        /** 입력 프레임 하나: 표식으로 어느 글의 음성인지 알고, 음성 뒤 무음이 이어지면 그 글을 입력 전사로 보낸다. */
        private void onInputFrame(byte[] pcm) {
            boolean voiced = false;
            for (byte sample : pcm) {
                if (sample != 0) {
                    voiced = true;
                    break;
                }
            }
            if (voiced) {
                if (voicedFrames == 0) {
                    heardId = pcm.length >= 4 && pcm[0] == MARK && pcm[1] == MARK ? (pcm[2] & 0xFF) | ((pcm[3] & 0xFF) << 8) : -1;
                }
                voicedFrames++;
                silentAfterVoice = 0;
                return;
            }
            if (voicedFrames > 0 && ++silentAfterVoice >= END_SILENCE_FRAMES) {
                voicedFrames = 0;
                silentAfterVoice = 0;
                String said = speech.textOf(heardId);
                if (said != null) {
                    String transcript = hearing.apply(said);
                    worker.execute(() -> transcribe(transcript));
                }
            }
        }

        /** 입력 전사 조각 둘(공급자는 완료 사건을 주지 않는다 — 실행부가 조각을 묶어 확정한다). */
        private void transcribe(String transcript) {
            try {
                int middle = transcript.offsetByCodePoints(0, transcript.codePointCount(0, transcript.length()) / 2);
                long clock = server.audioClockMs();
                server.emitInputTranscript(transcript.substring(0, middle), clock, clock);
                sleep(TRANSCRIPT_GAP);
                server.emitInputTranscript(transcript.substring(middle), clock, clock);
                inputTranscripts.add(transcript);
            } catch (IllegalStateException closed) {
                // 연결이 닫혔다
            }
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node == null ? null : node.get(field);
            return value == null || value.isNull() ? null : value.asString();
        }

        @Override
        public void close() {
            worker.shutdownNow();
            server.close();
        }
    }

    /**
     * 루프백 가짜 TTS({@code POST /v1/audio/speech}). 받은 글마다 번호를 매기고 표식이 든 음 PCM(글자 수에 비례, 0.16 ~ 0.8초)을 돌려준다. 요청 본문의
     * 모양(model · voice · 형식 · 지시 유무)과 Authorization이 Bearer 형식인지만 남긴다(키 값은 남기지 않는다). 실제 공급자가 아니다.
     */
    static final class FakeSpeech implements AutoCloseable {

        final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        final List<Boolean> bearer = new CopyOnWriteArrayList<>();
        private final Map<Integer, String> texts = new ConcurrentHashMap<>();
        private final List<String> inputs = new CopyOnWriteArrayList<>();
        /** 3회차 V1: 이 글이 오면 이 상태 코드로 거절한다(대본 줄 음성 합성 실패 흉내). */
        private final Map<String, Integer> failures = new ConcurrentHashMap<>();
        private final AtomicInteger ids = new AtomicInteger();
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private FakeSpeech() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(executor);
            server.createContext("/v1/audio/speech", this::handle);
            server.start();
        }

        static FakeSpeech start() throws IOException {
            return new FakeSpeech();
        }

        URI baseUrl() {
            return URI.create("http://" + server.getAddress().getAddress().getHostAddress() + ":" + server.getAddress().getPort() + "/v1");
        }

        String textOf(int id) {
            return texts.get(id);
        }

        /** 받은 글(순서대로, 합성 대역이 받은 시험 자료다). */
        List<String> inputs() {
            return List.copyOf(inputs);
        }

        /** 이 글을 이 상태 코드로 거절한다. */
        FakeSpeech failOn(String text, int status) {
            failures.put(text, status);
            return this;
        }

        @SuppressWarnings("unchecked")
        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                Map<String, Object> body = JSON.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Map.class);
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                bearer.add(authorization != null && authorization.startsWith("Bearer ") && authorization.length() > 7);
                Map<String, Object> shape = new java.util.LinkedHashMap<>();
                shape.put("model", body.get("model"));
                shape.put("voice", body.get("voice"));
                shape.put("response_format", body.get("response_format"));
                if (body.containsKey("instructions")) {
                    shape.put("instructions", true);
                }
                requests.add(shape);
                String text = String.valueOf(body.get("input"));
                inputs.add(text);
                Integer failure = failures.get(text);
                if (failure != null) {
                    exchange.sendResponseHeaders(failure, -1);
                    return;
                }
                int id = ids.incrementAndGet();
                texts.put(id, text);
                byte[] pcm = speechPcm(id, Math.max(8, Math.min(40, text.length() * 2)));
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, pcm.length);
                try (OutputStream response = exchange.getResponseBody()) {
                    response.write(pcm);
                }
            }
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
