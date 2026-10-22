package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.luna.LunaHosts;
import kr.mujoong.realtime.practiceflow.FlowState.Phase;
import kr.mujoong.realtime.practiceflow.PracticeDialogueLiveTest.AiChild;
import kr.mujoong.realtime.practiceflow.PracticeDialogueLiveTest.ChildAct;
import kr.mujoong.realtime.practiceflow.PracticeDialogueLiveTest.ChildCue;
import kr.mujoong.realtime.practiceflow.PracticeDialogueLiveTest.Heard;
import kr.mujoong.realtime.practiceflow.PracticeDialogueLiveTest.ScriptActor;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 실호출 대화 하니스({@link PracticeDialogueLiveTest})의 배선 점검. 실제 AI를 부르지 않는다: 루프백 가짜 Responses API가 판정 · 텍스트 대리 · AI 아이
 * 요청을 받아 정해진 답을 주고, 하니스가 세 아이 방식(대본 · AI · 규칙)을 끝까지 돌려 JSON 결과와 사람이 읽는 대화 기록을 쓰는지 본다. 증거 종류는
 * {@code FAKE_PROVIDER_SELF_CHECK}이고 키는 가짜 문자열이다(R-F1 · R-F3 · R-M11). 실호출 하니스를 돌리기 전에 배선이 깨지지 않았는지를 기본 시험에서
 * 확인하려는 것이다.
 */
@Tag("practice-flow")
class PracticeDialogueHarnessSelfCheckTest {

    private static final String FAKE_KEY = "test-key-not-real-practice-dialogue";
    private static final JsonCodec JSON = JsonCodec.shared();

    @TempDir
    Path out;

    @Test
    void theHarnessRunsTheScriptAiAndRuleChildrenAgainstALoopbackFakeAndWritesTheReadableTranscript() throws Exception {
        try (FakeResponses fake = FakeResponses.start()) {
            PracticeDialogueLiveTest.Endpoint endpoint = new PracticeDialogueLiveTest.Endpoint(fake.baseUrl(), LunaHosts.Mode.TEST,
                    () -> FAKE_KEY, "FAKE_PROVIDER_SELF_CHECK");

            PracticeDialogueLiveTest.Harness.Outcome outcome = new PracticeDialogueLiveTest.Harness(out, endpoint).run();

            assertThat(outcome.completedRuns()).isPositive();
            assertThat(outcome.childCalls()).as("AI 아이 호출").isPositive();
            assertThat(fake.requests).as("하니스 호출 상한 안").hasSizeLessThanOrEqualTo(outcome.cap());
            assertThat(outcome.judgmentCalls() + outcome.textCalls() + outcome.childCalls()).isEqualTo(fake.requests.size());

            String json = Files.readString(out.resolve(PracticeDialogueLiveTest.OUTPUT_FILE), StandardCharsets.UTF_8);
            assertThat(json.replaceAll("\\s", "")).contains("\"evidence\":\"FAKE_PROVIDER_SELF_CHECK\"", "\"childMode\":\"SCRIPT\"",
                    "\"childMode\":\"AI\"", "\"childMode\":\"RULE\"", "\"outcome\":\"COMPLETED\"").doesNotContain(FAKE_KEY);

            String md = Files.readString(out.resolve(PracticeDialogueLiveTest.MARKDOWN_FILE), StandardCharsets.UTF_8);
            assertThat(md).contains("# 연습 대화 기록", "FAKE_PROVIDER_SELF_CHECK", "### 대본 아이 · STANDARD", "### AI가 연기하는 아이 · P1",
                    "### 규칙 문장 아이 · GOOD_IMMEDIATE", "점검표", "기준 대화 기대 판정과 같음: 5 / 5").doesNotContain(FAKE_KEY);
            // 대본 아이: 기준 연습 대화의 차례가 그 순서 · 그 화자로 적힌다
            String standard = md.substring(md.indexOf("### 대본 아이 · STANDARD"), md.indexOf("### AI가 연기하는 아이 · P1"));
            assertInOrder(standard,
                    "- 무중: 무중이랑 같이 공룡 그림 그린다고 생각해 보자.",
                    "- 친구: 알았어, 잠깐만!",
                    "- 친구: 자, 여기 초록색!",
                    "- 아이: 어… 이거 아닌데. (J2 PARTIAL 0.90 · 기대 J2 PARTIAL)",
                    "- 친구: ",
                    "- 아이: 파란색! 나 파란색 달라고 했어. (J3 DELIVERED 0.90 · 기대 J3 DELIVERED)",
                    "- 친구: ",
                    "- 무중: 그림 다 그렸네. 이번엔 쉬는 시간이야. 네가 친구한테 딸기 우유를 달라고 했어. 이어서 해 볼까?",
                    "- 아이: 응. (J4 ACCEPT 0.90 · 기대 J4 ACCEPT)",
                    "- 친구: 자, 초코 우유 여기!",
                    "- 아이: 음… 몰라. 뭐라고 해? (J2 HELP 0.90 · 기대 J2 HELP)",
                    "- 무중: 잠깐, 무중이 도와줄게",
                    "- 코치: ",
                    "- 아이: 아니, 나 딸기 우유 달라고 했어. (J5 FULL 0.90 · 기대 J5 FULL)",
                    "- 무중: 다시 친구 역할로 돌아갈게",
                    "- 친구: ",
                    "성공한 장면 뒤 친구 반응: BASE(AFTER_REASK) 있음 · VARIANT(AFTER_HELP) 있음",
                    "무중 · 친구 · 코치 말의 칭찬 · 채점 말: 없음",
                    "장면 수: 2(BASE · VARIANT)");

            // AI 아이 P2(모름 → 도움 수락 → 도움 뒤 자기 말로)는 도움 요청 → 코치 도움 → 다시 해 보기 → 친구 반응 길을 탄다
            String helped = md.substring(md.indexOf("### AI가 연기하는 아이 · P2"), md.indexOf("### AI가 연기하는 아이 · P3"));
            assertInOrder(helped, "BASE AFTER_HELP", "- 아이: 음… 몰라. 뭐라고 해? (J2 HELP 0.90)", "- 무중: 잠깐, 무중이 도와줄게", "- 코치: ",
                    "(J5 FULL 0.90)", "- 무중: 다시 친구 역할로 돌아갈게", "- 친구: ", "- 아이: 이제 그만할래. (J4 REFUSE 0.90)");

            // AI 아이는 들은 말과 행동 계획만 받는다: 장면 요점 · 판정 기준 · 대본 칸 이름은 가지 않는다
            List<Map<String, Object>> childRequests = fake.requests.stream()
                    .filter(request -> String.valueOf(request.get("instructions")).contains("합성 인물 '하늘이'")).toList();
            assertThat(childRequests).isNotEmpty().allSatisfy(request -> assertThat(JSON.write(request)).doesNotContain(
                    "달라고 한 것과 받은 것이 같은지", "원래 원한 것을 다시 분명히 말한다", "focusPoints", "expressionExamples", "judgmentCriteria",
                    "coreFunction", "PARTIAL", "DELIVERED"));
            assertThat(JSON.write(childRequests.getFirst())).as("첫 AI 아이 요청에는 표현 예시가 없다(아직 아무도 말하지 않았다)")
                    .doesNotContain("아니, 나 파란색 달라고 했어");
        }
    }

    @Test
    void theScriptChildPicksTheLineForThePointTheFlowWaitsForAndSkipsOrRepeatsWhenTheJudgeDisagrees() {
        ScriptActor actor = new ScriptActor(StandardPracticeDialogue.load().childTurns());
        ChildAct first = actor.act(cue(Phase.OPPORTUNITY, SceneKind.BASE, JudgmentPoint.J2));
        assertThat(first.text()).isEqualTo("어… 이거 아닌데.");

        // 판정이 기대와 달리 곧바로 장면을 닫으면, 흐름은 변형 제안(J4)을 기다린다: 장면 1의 남은 대사(J3)는 건너뛴다
        ChildAct accept = actor.act(cue(Phase.VARIANT_OFFER, SceneKind.BASE, JudgmentPoint.J4));
        assertThat(accept.text()).isEqualTo("응.");
        assertThat(accept.detail()).containsEntry("skippedStandardTurns", List.of(2));

        // 같은 지점을 다시 기다리면(재안내) 그 장면의 같은 지점 대사를 되풀이한다
        assertThat(actor.act(cue(Phase.VARIANT_OFFER, SceneKind.BASE, JudgmentPoint.J4)).detail()).containsEntry("repeated", true);
        assertThat(actor.act(cue(Phase.OPPORTUNITY, SceneKind.VARIANT, JudgmentPoint.J2)).text()).isEqualTo("음… 몰라. 뭐라고 해?");
        assertThat(actor.act(cue(Phase.RETRY, SceneKind.VARIANT, JudgmentPoint.J5)).text()).isEqualTo("아니, 나 딸기 우유 달라고 했어.");
        ChildAct over = actor.act(cue(Phase.REASK, SceneKind.VARIANT, JudgmentPoint.J3));
        assertThat(over.end()).as("장면 2 대사를 다 썼다").isTrue();
    }

    @Test
    void theAiChildReplyIsTrimmedToWhatAChildSaysAndTheTurnHintOnlyNamesWhatWasHeard() {
        assertThat(AiChild.clean("하늘이: \"어… 이거 아닌데.\"")).isEqualTo("어… 이거 아닌데.");
        assertThat(AiChild.clean("응!\n나 파란색 달라고 했어. 그리고 또 있어. 진짜야.")).isEqualTo("응! 나 파란색 달라고 했어.");
        assertThat(AiChild.clean("음… 몰라. 뭐라고 해?")).as("말줄임은 문장 끝이 아니다").isEqualTo("음… 몰라. 뭐라고 해?");
        assertThat(AiChild.clean("음... 몰라. 뭐라고 해? 응?")).isEqualTo("음... 몰라. 뭐라고 해?");
        assertThat(AiChild.clean("가".repeat(300))).hasSize(AiChild.MAX_CHARS);
        assertThat(AiChild.clean(null)).isEmpty();
        assertThat(AiChild.hint(cue(Phase.OPPORTUNITY, SceneKind.BASE, JudgmentPoint.J2))).isEqualTo("친구가 방금 말을 걸었다(첫 대답)");
        assertThat(AiChild.hint(cue(Phase.REASK, SceneKind.BASE, JudgmentPoint.J3))).isEqualTo("친구가 되물었다");
        for (PracticeDialogueLiveTest.Plan plan : PracticeDialogueLiveTest.Plan.values()) {
            assertThat(AiChild.instructions(plan)).contains("합성 인물 '하늘이'", "행동 계획(" + plan.name())
                    .doesNotContain("PARTIAL", "J2", "판정", "표현 예시");
        }
        List<Map<String, Object>> input = AiChild.input(List.of(new Heard("무중", "무중이랑 놀자."), new Heard("아이", "응.")), "친구가 되물었다");
        assertThat(input).extracting(message -> message.get("role")).containsExactly("user", "assistant", "developer");
        assertThat(input.getFirst()).containsEntry("content", "무중: 무중이랑 놀자.");
    }

    private static ChildCue cue(Phase phase, SceneKind kind, JudgmentPoint expected) {
        return new ChildCue(phase, kind, 1, null, List.of(), expected);
    }

    private static void assertInOrder(String text, String... parts) {
        int from = 0;
        for (String part : parts) {
            int at = text.indexOf(part, from);
            assertThat(at).as("순서대로 '%s'", part).isGreaterThanOrEqualTo(0);
            from = at + part.length();
        }
    }

    /**
     * 루프백 가짜 Responses API. 판정 요청(구조화 출력)은 아이 대사에 맞춘 선택지를, AI 아이 요청은 행동 계획 · 차례 메모에 맞춘 대사를, 그 밖(친구 ·
     * 코치 텍스트 대리)은 고정 문장을 돌려준다. 실제 공급자가 아니다.
     */
    static final class FakeResponses implements AutoCloseable {

        /** 아이 대사 → 기대 선택지(기준 연습 대화 · 가짜 AI 아이 대사). 판정 지점의 선택지에 없으면 같은 뜻의 코드 · 첫 선택지로 간다. */
        private static final Map<String, String> OPTIONS = Map.ofEntries(
                Map.entry("어… 이거 아닌데.", "PARTIAL"),
                Map.entry("파란색! 나 파란색 달라고 했어.", "DELIVERED"),
                Map.entry("응.", "ACCEPT"),
                Map.entry("음… 몰라. 뭐라고 해?", "HELP"),
                Map.entry("아니, 나 딸기 우유 달라고 했어.", "FULL"),
                Map.entry("아니, 나 파란색 달라고 했어.", "FULL"),
                Map.entry("나 어제 공룡 박물관 갔어!", "IRRELEVANT"),
                Map.entry("응, 도와줘.", "ACCEPT"),
                Map.entry("응, 할래.", "ACCEPT"),
                Map.entry("이제 그만할래.", "REFUSE"));

        final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private FakeResponses() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }

        static FakeResponses start() throws IOException {
            return new FakeResponses();
        }

        URI baseUrl() {
            return URI.create("http://" + server.getAddress().getAddress().getHostAddress() + ":" + server.getAddress().getPort() + "/v1");
        }

        @SuppressWarnings("unchecked")
        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                Map<String, Object> request = JSON.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Map.class);
                requests.add(request);
                String output;
                if (request.get("text") instanceof Map<?, ?> text && text.get("format") instanceof Map<?, ?> format
                        && "json_schema".equals(format.get("type"))) {
                    output = judgment(JSON.read(String.valueOf(request.get("input")), Map.class));
                } else if (String.valueOf(request.get("instructions")).contains("합성 인물 '하늘이'")) {
                    output = child(String.valueOf(request.get("instructions")), (List<Map<String, Object>>) request.get("input"));
                } else {
                    output = "응? 그렇구나, 알겠어.";
                }
                byte[] bytes = JSON.write(response(output)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(bytes);
                }
            }
        }

        @SuppressWarnings("unchecked")
        private static String judgment(Map<String, Object> input) {
            List<String> options = (List<String>) input.get("options");
            String wanted = OPTIONS.getOrDefault(String.valueOf(input.get("childUtterance")), options.getFirst());
            if (!options.contains(wanted)) {
                wanted = "FULL".equals(wanted) && options.contains("DELIVERED") ? "DELIVERED" : options.getFirst();
            }
            String first = wanted;
            String second = options.stream().filter(option -> !option.equals(first)).findFirst().orElseThrow();
            return JSON.write(Map.of("first", Map.of("option", first, "probability", 0.9), "second", Map.of("option", second,
                    "probability", 0.05)));
        }

        /** 가짜 AI 아이: 행동 계획 이름과 마지막 차례 메모로 대사를 고른다. */
        private static String child(String instructions, List<Map<String, Object>> input) {
            String hint = String.valueOf(input.getLast().get("content"));
            String plan = instructions.substring(instructions.indexOf("행동 계획(") + "행동 계획(".length()).substring(0, 2);
            if (hint.contains("도와줘도 되는지")) {
                return "응, 도와줘.";
            }
            if (hint.contains("다른 장면으로 이어서 할지")) {
                return plan.equals("P1") || plan.equals("P3") ? "응, 할래." : "이제 그만할래.";
            }
            if (hint.contains("첫 대답")) {
                return switch (plan) {
                    case "P1" -> "어… 이거 아닌데.";
                    case "P2" -> "음… 몰라. 뭐라고 해?";
                    case "P4" -> "나 어제 공룡 박물관 갔어!";
                    default -> "아니, 나 파란색 달라고 했어.";
                };
            }
            return "아니, 나 파란색 달라고 했어.";
        }

        private static Map<String, Object> response(String text) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "output_text");
            part.put("text", text);
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "message");
            message.put("role", "assistant");
            message.put("content", List.of(part));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", "resp_fake");
            body.put("object", "response");
            body.put("status", "completed");
            body.put("model", "fake-model");
            body.put("output", new ArrayList<>(List.of(message)));
            body.put("usage", Map.of("input_tokens", 10, "output_tokens", 5, "total_tokens", 15));
            return body;
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
