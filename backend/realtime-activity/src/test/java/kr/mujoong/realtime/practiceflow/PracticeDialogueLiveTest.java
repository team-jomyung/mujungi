package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.dialog.DialogInstructions;
import kr.mujoong.realtime.dialog.LiveSessionInstructions;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import kr.mujoong.realtime.judgment.AbstainReason;
import kr.mujoong.realtime.judgment.JudgeOutcome;
import kr.mujoong.realtime.judgment.JudgeOutcomeValidator;
import kr.mujoong.realtime.judgment.JudgeRequest;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.judgment.JudgmentPrompt;
import kr.mujoong.realtime.judgment.LunaJudgeClient;
import kr.mujoong.realtime.luna.LunaException;
import kr.mujoong.realtime.luna.LunaHosts;
import kr.mujoong.realtime.luna.LunaInputSanitizer;
import kr.mujoong.realtime.luna.LunaProperties;
import kr.mujoong.realtime.luna.LunaSchemas;
import kr.mujoong.realtime.luna.LunaTransport;
import kr.mujoong.realtime.luna.RealtimeLunaClient;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.practicelegacy.PracticePorts.Playback;
import kr.mujoong.realtime.practiceflow.FlowState.Phase;
import kr.mujoong.realtime.practiceflow.FlowState.Sub;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.MemoryRecorder;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.VariantSkipReason;
import kr.mujoong.realtime.practiceflow.PracticeScript.Scene;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.PracticeSessionExtension.ExtensionEvent;
import kr.mujoong.realtime.session.PracticeSessionExtension.PlaybackFact;
import kr.mujoong.realtime.session.PracticeSessionExtension.PracticeContext;
import kr.mujoong.realtime.session.PracticeSessionExtension.PracticeOutput;
import kr.mujoong.realtime.session.PracticeSessionExtension.Signal;
import kr.mujoong.realtime.session.PracticeSessionExtension.Utterance;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.realtime.voice.PracticeInstruction;
import kr.mujoong.realtime.voice.PracticeSpeech;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실호출 연습 대화 모의 하니스 — realtime. "만들어진 연습이 6~12세 아이의 화용 능력을 연습할 수 있는 연습이 되는가"를 사람이 읽어 평가할 수 있게,
 * core 하니스({@code PracticeQualityLiveTest})가 실제 Luna로 만든 시작 패키지를 실제 {@link PracticeConversation}(운영 상태 기계)으로 진행하는
 * '텍스트 대화 모의'를 돌려 대화 기록을 파일로 남긴다.
 *
 * <ul>
 *   <li>기본 {@code test} · {@code check} · CI에서는 돌지 않는다({@code @Tag("luna-reach")}, build-logic 규약이 기본 test에서 빼고 키도 지운다).
 *       사람이 env {@code OPENAI_API_KEY}를 넣고 {@code ./gradlew :realtime-activity:lunaReachTest --tests "*PracticeDialogueLiveTest*"}로만
 *       돈다. 새 Gradle 작업은 없다. 키가 없으면 출력 파일에 {@code SKIPPED_NO_KEY}만 쓰고 건너뛴다(통과로 보고하지 않음). 키는 env에 있는지만
 *       보고 값을 변수에 담지 않는다(전송부가 보낼 때마다 env에서 읽는다).
 *   <li>아이 쪽은 대본으로 정한 합성 반응 4종({@link Reaction})을 목표마다 돌린다. 반응 문장은 장면의 표현 예시 · 목표 표현에서 정해진 규칙으로
 *       만든다(랜덤 없음).
 *   <li>판정(J2 ~ J5)은 운영 조립 그대로다: 확장점 요청을 실행부({@code SessionExecutor.requestExtensionJudgment} ·
 *       {@code JudgmentCoordinator.open})와 같은 {@link JudgeRequest}로 바꾸고 {@link LunaJudgeClient} → {@link RealtimeLunaClient}(STRICT,
 *       https + api.openai.com) → {@link JudgeOutcomeValidator}로 부른다. 시도 시한만 넉넉히 주고 운영 live-budget과 비교해 적는다.
 *   <li>친구 · 코치 생성형 발화는 원래 GPT-Live 음성이다. 여기서는 {@link PracticeOutput#speakGenerated}의 지시문을 가로채, 운영과 같은 세션 지시문
 *       (공통 역할 문장 + 연습 활동 본문 {@code live-prompts/practice.v1.txt})과 같은 범위 닫힌 추가 지시를 텍스트 모델(Responses API, realtime
 *       {@link LunaTransport})로 보내 텍스트 발화를 받는다. 출력에는 {@code textProxy: true}('GPT-Live 음성 아님, 텍스트 대리')로 표시한다.
 *   <li>승인 발화(장면 설명 · 기회 문장 · 상황 다시 설명 · 역할 전환 알림)는 모델을 부르지 않고 승인 원문 그대로 기록한다.
 *   <li>입력은 합성 자료뿐이다. 출력에는 평가용 합성 대본 · 발화 원문을 담고 키 · 요청 헤더 · 주소는 담지 않는다. 증거 종류는 '실제 API · 합성
 *       자료'다(테스트 대역 · 실제 기기 · 실제 GPT-Live 음성 증거 아님, R-M11). 콘솔에는 개수와 파일 위치만 쓴다(R-F14).
 *   <li>OpenAI 호출은 core 하니스와 합쳐 {@value #TOTAL_CALL_BUDGET}회 안쪽이 되게 상한을 둔다(core 요약의 실제 호출 수를 뺀 값, 최대
 *       {@value #REALTIME_CALL_MAX}회).
 * </ul>
 *
 * <p>입력: 출력 폴더의 {@code practice-start-packages/*.json}(core 요약 순서), 없으면 {@code contracts/fixtures/practice-start-package.json}
 * 하나. 출력 위치: 시스템 속성 {@code mujoong.live.eval.out} → env {@code MUJOONG_LIVE_EVAL_OUT} → {@code build/live-eval/} 순으로 고르고 그 아래
 * {@code practice-dialogues.json}을 쓴다. 지난 실호출 결과는 지우지 않고 {@code previous-run/}으로 옮긴다.
 */
@Tag("luna-reach")
class PracticeDialogueLiveTest {

    static final String OUTPUT_FILE = "practice-dialogues.json";
    static final String PACKAGE_DIR = "practice-start-packages";
    static final String CORE_SUMMARY_FILE = "core-practice-packages.json";
    static final String PREVIOUS_DIR = "previous-run";
    static final String SKIPPED = "SKIPPED_NO_KEY";
    static final String EVIDENCE = "REAL_API_SYNTHETIC";
    static final String FIXTURE = "contracts/fixtures/practice-start-package.json";
    static final String APPROVED_SPEECH_SEED = "infra/seed/004-u8-reference.sql";

    /** core 하니스와 합친 전체 OpenAI 호출 목표 상한. */
    static final int TOTAL_CALL_BUDGET = 150;
    /** core 요약을 읽지 못했을 때 core가 썼다고 보는 호출 수(core 하니스의 최악 51회). */
    static final int CORE_WORST_CASE_CALLS = 51;
    /** 이 하니스 혼자 쓰는 호출의 최대. */
    static final int REALTIME_CALL_MAX = 110;

    // 하니스 전용 넉넉한 시한(운영 값은 application.yml에서 읽어 비교만 한다). 시한 하나가 넘치면 그 대화가 보류 · 실패 쪽으로 꺾이므로 넉넉히 둔다
    static final Duration JUDGE_LIMIT = Duration.ofSeconds(60);
    static final Duration TEXT_LIMIT = Duration.ofSeconds(90);
    static final Duration OUTER_MARGIN = Duration.ofSeconds(10);

    /** 대화 하나의 안전 한도(전이표가 끝나지 않는 일은 없지만 하니스가 멈추지 않게). */
    static final int MAX_STEPS = 300;
    static final int MAX_CHILD_TURNS = 16;
    /** 실행부와 같은 최근 아이 확정 전사 보관 수({@code JudgmentCoordinator.RECENT_LIMIT}). */
    static final int RECENT_LIMIT = 4;

    /** 합성 아동 프로필(실제 아동 아님, core 하니스와 같은 값). 이름은 판정 입력에서 별칭 '아이'로 바뀐다. */
    static final String SYNTHETIC_NAME = "합성아이";
    static final int SYNTHETIC_AGE = 8;
    static final List<String> SYNTHETIC_INTERESTS = List.of("공룡", "그림");
    static final String CHILD_ALIAS = "아이";

    /** 텍스트 대리에만 덧붙이는 하니스 메모(운영 세션 지시문에는 없다, 출력에 그대로 남긴다). */
    static final String TEXT_PROXY_NOTE = "[시험 하니스 메모] 이 대화는 음성 대신 글로 시험한다. 아이에게 소리 내어 할 말만 그대로 쓰고, "
            + "설명 · 따옴표 · 역할 이름표는 붙이지 않는다.";

    private static final JsonCodec JSON = JsonCodec.shared();
    private static final JsonMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final Pattern MODEL_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern SAFE_TOKEN = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Pattern FIXED_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    @Test
    void scriptedSyntheticRepliesRunThroughThePracticeFlowWithRealLunaJudgments() throws IOException {
        Path out = outputDirectory();
        boolean keyPresent = keyPresent();
        if (!keyPresent) {
            writeSkipped(out);
        }
        assumeTrue(keyPresent, SKIPPED + ": env OPENAI_API_KEY 없음(실호출 안 함, 통과 아님)");

        Harness.Outcome outcome = new Harness(out).run();

        // 콘솔에는 개수와 파일 위치만 쓴다(대화 원문은 파일에만, R-F14)
        System.out.println(outcome.consoleLine());
        assertThat(outcome.completedRuns()).as("끝까지 진행된 대화 수(" + outcome.file() + ")").isPositive();
        assertThat(outcome.answeredJudgments()).as("공급자가 답한 판정 수(" + outcome.file() + ")").isPositive();
    }

    // ------------------------------------------------------------------ 출력 위치 · 키 없음

    static Path outputDirectory() {
        String property = System.getProperty("mujoong.live.eval.out");
        String env = System.getenv("MUJOONG_LIVE_EVAL_OUT");
        String chosen = property != null && !property.isBlank() ? property
                : env != null && !env.isBlank() ? env : "build/live-eval";
        return Path.of(chosen).toAbsolutePath().normalize();
    }

    /** 키가 있는지만 본다. 값은 변수에 담지 않는다. */
    static boolean keyPresent() {
        return Optional.ofNullable(System.getenv(LunaTransport.API_KEY_ENV)).filter(value -> !value.isBlank()).isPresent();
    }

    static Path repoRoot() {
        String root = System.getProperty("mujoong.repo.root");
        return (root == null || root.isBlank() ? Path.of("..", "..") : Path.of(root)).toAbsolutePath().normalize();
    }

    /** 키 없음: 출력 파일에 SKIPPED_NO_KEY만 쓴다. 지난 실호출 결과가 있으면 덮기 전에 previous-run/으로 옮긴다. */
    static void writeSkipped(Path out) throws IOException {
        Files.createDirectories(out);
        movePreviousResult(out);
        Files.writeString(out.resolve(OUTPUT_FILE), "{\"outcome\":\"" + SKIPPED + "\"}" + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    /** 지난 실호출 결과(SKIPPED가 아닌 것)는 지우지 않고 previous-run/으로 옮긴다. */
    static void movePreviousResult(Path out) throws IOException {
        Path current = out.resolve(OUTPUT_FILE);
        if (Files.exists(current) && !Files.readString(current, StandardCharsets.UTF_8).contains(SKIPPED)) {
            Path previous = Files.createDirectories(out.resolve(PREVIOUS_DIR));
            Files.move(current, previous.resolve(OUTPUT_FILE), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ 합성 아이 반응

    /**
     * 아이 쪽 합성 반응 4종. 모든 문장은 아래 규칙으로만 정한다(랜덤 없음). 반응 유형 이름은 하니스의 의도이고, 판정이 다르게 나오면 그것도 평가
     * 자료다(규칙 문장이 의도한 반응을 정확히 담지 못했을 수도 있다).
     */
    enum Reaction {
        GOOD_IMMEDIATE("바로 목표 행동을 잘함", 5,
                "기회 문장 뒤 첫 대답부터 목표 표현을 끝까지 말한다. 되묻기 · 다시 해 보기에서도 같은 표현, 도움 제안에는 '" + Child.ACCEPT_HELP
                        + "', 변형 제안에는 '" + Child.ACCEPT_VARIANT + "'(변형 장면도 같은 규칙)."),
        PARTIAL_CUE_MISSING("부분적으로 함(단서 일부 빠짐)", 6,
                "첫 대답은 목표 표현의 앞쪽 어절만 말한다(어절 n개면 앞 ⌈n/2⌉개, 끝 문장부호 뺌). 되묻기 · 두 번째 기회 · 다시 해 보기에서는 목표 "
                        + "표현을 끝까지 말한다. 도움 제안에는 '" + Child.ACCEPT_HELP + "', 변형 제안에는 '" + Child.DECLINE_VARIANT + "'."),
        OFF_TOPIC("엉뚱한 말 · 주제 이탈", 5,
                "첫 대답은 장면 글과 낱말이 겹치지 않는 고정 잡담 문장(목록 순서로 첫 번째로 겹치지 않는 것)이다. 상황 다시 설명 뒤 두 번째 기회 · "
                        + "되묻기 · 다시 해 보기에서는 목표 표현을 끝까지 말한다. 도움 제안에는 '" + Child.ACCEPT_HELP + "', 변형 제안에는 '"
                        + Child.DECLINE_VARIANT + "'."),
        SILENT_THEN_HELP("무응답 → 도움 동의 → 도움 뒤 다시 시도", 8,
                "첫 기회에는 말하지 않는다(무응답 시계 만료). 다시 안내 뒤의 기회 · 되묻기에는 '" + Child.HESITATION + "', 도움 제안에는 '"
                        + Child.ACCEPT_HELP + "'(도움 동의), 코치 도움 뒤 다시 해 보기에서는 목표 표현을 끝까지 말한다. 변형 제안에는 '"
                        + Child.DECLINE_VARIANT + "'.");

        final String label;
        /** 이 대화를 시작하기 전에 남아 있어야 하는 호출 수(보통 쓰는 수 기준). */
        final int reserve;
        final String rule;

        Reaction(String label, int reserve, String rule) {
            this.label = label;
            this.reserve = reserve;
            this.rule = rule;
        }
    }

    /** 장면 하나에서 아이가 쓸 문장들(규칙으로 미리 정한다). */
    record SceneLines(String full, String fullSource, String partial, String offTopic) {

        Map<String, Object> report() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("full", full);
            map.put("fullSource", fullSource);
            map.put("partial", partial);
            map.put("offTopic", offTopic);
            return map;
        }
    }

    /** 아이 한 번의 행동. {@code text}가 null이면 말하지 않는다(무응답). */
    record ChildAct(String text, String rule) {
        boolean silent() {
            return text == null;
        }
    }

    /** 반응 규칙과 고정 문장. */
    static final class Child {

        static final String ACCEPT_HELP = "응, 도와줘.";
        static final String ACCEPT_VARIANT = "응, 한 번 더 해 볼래.";
        static final String DECLINE_VARIANT = "아니, 이제 괜찮아.";
        static final String HESITATION = "음, 잘 모르겠어.";

        /** 잡담 후보와 그 낱말(장면 글에 낱말이 하나도 없는 첫 후보를 쓴다). */
        static final List<Map.Entry<String, List<String>>> OFF_TOPIC_CANDIDATES = List.of(
                Map.entry("근데 나 어제 공룡 박물관 갔다? 티라노가 엄청 컸어.", List.of("공룡", "티라노", "박물관")),
                Map.entry("오늘 급식에 떡볶이 나왔어. 나 떡볶이 좋아해.", List.of("급식", "떡볶이")),
                Map.entry("우리 집 강아지가 어제 내 양말 물고 도망갔어.", List.of("강아지", "양말")));

        private Child() {
        }

        static SceneLines lines(Scene scene, String targetExpression, String fourElementsExpression) {
            String full;
            String source;
            if (!scene.expressionExamples().isEmpty() && !unquote(scene.expressionExamples().getFirst()).isBlank()) {
                full = unquote(scene.expressionExamples().getFirst());
                source = "scene.expressionExamples[0]";
            } else if (targetExpression != null && !targetExpression.isBlank()) {
                full = unquote(targetExpression);
                source = "goalContext.targetExpression";
            } else if (fourElementsExpression != null && !fourElementsExpression.isBlank()) {
                full = unquote(fourElementsExpression);
                source = "goalContext.fourElements.expression";
            } else {
                full = unquote(scene.opportunitySentence());
                source = "scene.opportunitySentence(표현 없음 — 기회 문장을 따라 말함)";
            }
            String sceneText = String.join(" ", scene.sceneDescription(), scene.opportunitySentence(), scene.reexplanation(),
                    scene.topic(), scene.place(), scene.relation(), String.join(" ", scene.focusPoints()),
                    String.join(" ", scene.expressionExamples()), full);
            return new SceneLines(full, source, partial(full), offTopic(sceneText));
        }

        /** 앞쪽 ⌈n/2⌉어절(끝 문장부호 뺌). 한 어절뿐이면 앞쪽 절반 글자(4자 미만이면 머뭇거림 '음'). */
        static String partial(String full) {
            String[] words = full.strip().split("\\s+");
            String kept;
            if (words.length >= 2) {
                kept = String.join(" ", Arrays.asList(words).subList(0, (words.length + 1) / 2));
            } else {
                String word = words[0];
                int points = word.codePointCount(0, word.length());
                kept = points >= 4 ? word.substring(0, word.offsetByCodePoints(0, points / 2)) : "음";
            }
            return kept.replaceAll("[\\s,.!?~…·]+$", "");
        }

        static String offTopic(String sceneText) {
            for (Map.Entry<String, List<String>> candidate : OFF_TOPIC_CANDIDATES) {
                if (candidate.getValue().stream().noneMatch(sceneText::contains)) {
                    return candidate.getKey();
                }
            }
            return OFF_TOPIC_CANDIDATES.getLast().getKey();
        }

        /** 반응 규칙: 단계 · 그 단계에서 몇 번째 입력인가 · 장면 문장으로 정한다. */
        static ChildAct act(Reaction reaction, Phase phase, int nth, SceneLines lines) {
            return switch (phase) {
                case HELP_OFFER -> new ChildAct(ACCEPT_HELP, "도움 제안 → 동의");
                case VARIANT_OFFER -> reaction == Reaction.GOOD_IMMEDIATE
                        ? new ChildAct(ACCEPT_VARIANT, "변형 제안 → 수락")
                        : new ChildAct(DECLINE_VARIANT, "변형 제안 → 사양");
                case RETRY -> new ChildAct(lines.full(), "다시 해 보기 → 목표 표현 끝까지(" + lines.fullSource() + ")");
                case REASK -> reaction == Reaction.SILENT_THEN_HELP
                        ? new ChildAct(HESITATION, "되묻기 → 머뭇거림")
                        : new ChildAct(lines.full(), "되묻기 → 목표 표현 끝까지(" + lines.fullSource() + ")");
                case OPPORTUNITY -> opportunity(reaction, nth, lines);
                default -> new ChildAct(lines.full(), "입력 대기 단계 밖(예외 규칙) → 목표 표현");
            };
        }

        private static ChildAct opportunity(Reaction reaction, int nth, SceneLines lines) {
            String fullRule = "기회 " + nth + "번째 → 목표 표현 끝까지(" + lines.fullSource() + ")";
            return switch (reaction) {
                case GOOD_IMMEDIATE -> new ChildAct(lines.full(), fullRule);
                case PARTIAL_CUE_MISSING -> nth == 1
                        ? new ChildAct(lines.partial(), "기회 1번째 → 목표 표현 앞쪽 어절만")
                        : new ChildAct(lines.full(), fullRule);
                case OFF_TOPIC -> nth == 1
                        ? new ChildAct(lines.offTopic(), "기회 1번째 → 장면과 겹치지 않는 잡담")
                        : new ChildAct(lines.full(), fullRule);
                case SILENT_THEN_HELP -> nth == 1
                        ? new ChildAct(null, "기회 1번째 → 무응답(무응답 시계 만료까지 말하지 않음)")
                        : new ChildAct(HESITATION, "기회 " + nth + "번째 → 머뭇거림");
            };
        }

        private static String unquote(String value) {
            String text = value == null ? "" : value.strip();
            while (text.length() >= 2 && "\"'“”‘’「」".indexOf(text.charAt(0)) >= 0
                    && "\"'“”‘’「」".indexOf(text.charAt(text.length() - 1)) >= 0) {
                text = text.substring(1, text.length() - 1).strip();
            }
            return text;
        }
    }

    // ------------------------------------------------------------------ 운영 설정

    /** application.yml의 운영 값(시험 값을 지어내지 않는다). */
    record Production(Duration noResponseWait, Duration focusMax, Duration goalActiveLimit, Duration slotGuard, int retryMax,
            int reexplainMax, Duration liveBudget, double minConfidence, double minMargin, URI lunaBaseUrl, int maxResponseBytes,
            int maxOutputTokens, String reasoningEffort, String lunaModelSetting) {

        private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([A-Za-z0-9_]+):([^}]*)}$");

        static Production load() {
            List<PropertySource<?>> sources;
            try {
                sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
            } catch (IOException unreadable) {
                throw new IllegalStateException("application.yml을 읽지 못했다");
            }
            // 자리 표시자는 풀지 않는다: 모델 키는 ${ENV:기본값} 원문을 받아 기본값만 꺼낸다
            Binder binder = new Binder(ConfigurationPropertySources.from(sources), null, ApplicationConversionService.getSharedInstance());
            return new Production(
                    duration(binder, "mujoong.timing.conversation.no-response-wait"),
                    duration(binder, "mujoong.timing.conversation.focus-max"),
                    duration(binder, "mujoong.timing.conversation.goal-active-limit"),
                    duration(binder, "mujoong.timing.device.output-queue-max"),
                    bound(binder, "mujoong.practice-flow.retry-max", Bindable.of(Integer.class)),
                    bound(binder, "mujoong.practice-flow.reexplain-max", Bindable.of(Integer.class)),
                    duration(binder, "mujoong.timing.judgment.live-budget"),
                    bound(binder, "mujoong.judgment.min-confidence", Bindable.of(Double.class)),
                    bound(binder, "mujoong.judgment.min-margin", Bindable.of(Double.class)),
                    URI.create(bound(binder, "mujoong.luna.base-url", Bindable.of(String.class))),
                    bound(binder, "mujoong.luna.max-response-bytes", Bindable.of(Integer.class)),
                    bound(binder, "mujoong.luna.purpose.judgment-live.max-output-tokens", Bindable.of(Integer.class)),
                    bound(binder, "mujoong.luna.purpose.judgment-live.reasoning-effort", Bindable.of(String.class)),
                    bound(binder, "mujoong.luna.model", Bindable.of(String.class)));
        }

        PracticeFlowSettings settings() {
            return new PracticeFlowSettings(noResponseWait, focusMax, goalActiveLimit, slotGuard, retryMax, reexplainMax);
        }

        /** {@code mujoong.luna.model}의 {@code ${ENV:기본값}}에서 기본값(자리 표시자가 아니면 값 그대로). */
        String lunaModelDefault() {
            Matcher matcher = PLACEHOLDER.matcher(lunaModelSetting.strip());
            return matcher.matches() ? matcher.group(2).strip() : lunaModelSetting.strip();
        }

        Map<String, Object> report() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("noResponseWait", noResponseWait.toString());
            map.put("focusMax", focusMax.toString());
            map.put("goalActiveLimit", goalActiveLimit.toString());
            map.put("slotGuard(mujoong.timing.device.output-queue-max)", slotGuard.toString());
            map.put("retryMax", retryMax);
            map.put("reexplainMax", reexplainMax);
            map.put("judgmentLiveBudget", liveBudget.toString());
            map.put("judgmentMinConfidence", minConfidence);
            map.put("judgmentMinMargin", minMargin);
            map.put("lunaMaxOutputTokens(judgment-live)", maxOutputTokens);
            map.put("lunaReasoningEffort(judgment-live)", reasoningEffort);
            map.put("source", "realtime application.yml(운영 값을 읽기만 함)");
            return map;
        }

        private static Duration duration(Binder binder, String key) {
            return bound(binder, key, Bindable.of(Duration.class));
        }

        private static <T> T bound(Binder binder, String key, Bindable<T> type) {
            return binder.bind(key, type).orElseThrow(() -> new IllegalStateException("application.yml에 " + key + " 없음"));
        }
    }

    // ------------------------------------------------------------------ 호출 상한

    /** 하니스 전체 OpenAI 호출 수. 실호출 직전에만 센다. */
    static final class CallBudget {

        final int cap;
        int judgment;
        int text;

        CallBudget(int cap) {
            this.cap = cap;
        }

        int used() {
            return judgment + text;
        }

        int remaining() {
            return Math.max(0, cap - used());
        }

        boolean available() {
            return used() < cap;
        }
    }

    // ------------------------------------------------------------------ 보낼 곳

    /**
     * 보낼 곳. 실호출은 언제나 {@link #production}(application.yml 주소 · STRICT 규칙 · 보낼 때마다 env에서 읽는 키)이다. 다른 값은 하니스 배선
     * 점검(가짜 공급자)만 넣고, 그때 증거 종류는 {@code FAKE_PROVIDER_SELF_CHECK}다(대역 성공을 실호출 성공으로 적지 않음, R-M11).
     */
    record Endpoint(URI baseUrl, LunaHosts.Mode mode, Supplier<String> apiKey, String evidence) {

        static Endpoint production(Production production) {
            return new Endpoint(production.lunaBaseUrl(), LunaHosts.modeFor(), () -> System.getenv(LunaTransport.API_KEY_ENV), EVIDENCE);
        }

        @Override
        public String toString() {
            return "Endpoint[mode=" + mode + ", evidence=" + evidence + "]";
        }
    }

    // ------------------------------------------------------------------ 판정 (운영 조립)

    /** 운영 판정 부품: {@link LunaJudgeClient}(판정 지시문 · 결과 검증) → {@link RealtimeLunaClient}(JUDGMENT_LIVE, STRICT 주소 규칙). */
    static final class Judge implements AutoCloseable {

        final LunaProperties properties;
        final LiveContentResources.JudgmentOptionSet optionSet;
        final JudgmentPrompt prompt;
        private final LunaTransport transport;
        private final LunaJudgeClient client;
        private final CallBudget budget;

        Judge(Production production, CallBudget budget, Endpoint endpoint) {
            this.budget = budget;
            MujoongClock clock = new SystemMujoongClock();
            // 모델 이름만 env(운영과 같은 OPENAI_JUDGE_MODEL, 비면 운영 기본 모델). 주소 · 한도 · 추론 강도는 application.yml 값이다
            properties = new LunaProperties(System.getenv("OPENAI_JUDGE_MODEL"), endpoint.baseUrl(), production.maxResponseBytes(),
                    new LunaProperties.Purposes(new LunaProperties.PurposeOptions(production.maxOutputTokens(),
                            production.reasoningEffort())), true);
            transport = new LunaTransport(properties.baseUrl(), endpoint.mode(), endpoint.apiKey(), properties.maxResponseBytes());
            RealtimeLunaClient luna = new RealtimeLunaClient(properties, JUDGE_LIMIT, LunaSchemas.loadDefault(JSON),
                    new LunaInputSanitizer(JSON), transport,
                    TestEventLoggers.strict(clock, LogCapture.detached(), RealtimeMetrics.inMemory()), clock, JSON);
            optionSet = LiveContentResources.loadDefault().judgment();
            prompt = JudgmentPrompt.load(optionSet);
            client = new LunaJudgeClient(request -> {
                budget.judgment++;
                return luna.call(request);
            }, prompt, new JudgeOutcomeValidator(production.minConfidence(), production.minMargin(), JSON), clock);
        }

        JudgeOutcome judge(JudgeRequest request) {
            CompletableFuture<JudgeOutcome> future = client.judge(request, UUID.randomUUID()).toCompletableFuture();
            try {
                return future.get(JUDGE_LIMIT.plus(OUTER_MARGIN).toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException late) {
                future.cancel(true);
                return JudgeOutcome.abstained(AbstainReason.TIMEOUT, JUDGE_LIMIT.plus(OUTER_MARGIN), null, prompt.version());
            } catch (ExecutionException failed) {
                // LunaJudgeClient는 실패를 보류로 완료하므로 여기는 오지 않는다. 와도 공급자 오류 보류로 이어 간다
                return JudgeOutcome.abstained(AbstainReason.PROVIDER_ERROR, Duration.ZERO, null, prompt.version());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("판정 대기가 중단됐다", interrupted);
            }
        }

        boolean available() {
            return budget.available();
        }

        @Override
        public void close() {
            transport.close();
        }
    }

    // ------------------------------------------------------------------ 텍스트 대리 (GPT-Live 대신)

    /** 텍스트 대리 호출 한 번의 결과. 실패면 {@code text}가 null이다. */
    record TextReply(String outcome, String model, String text, long latencyMillis, Map<String, Long> usage, String errorCode) {
        boolean ok() {
            return "OK".equals(outcome);
        }
    }

    /**
     * GPT-Live에 보낼 지시를 텍스트 모델로 보내는 대리. 전송은 realtime {@link LunaTransport}(STRICT 주소 규칙, 키는 보낼 때 env에서 읽음)이고 본문은
     * Responses API 텍스트 요청이다(구조화 출력 없음). 응답 원문은 발화 텍스트만 쓴다.
     */
    static final class TextProxy implements AutoCloseable {

        final String model;
        final String modelSource;
        private final LunaTransport transport;
        private final int maxOutputTokens;
        private final String reasoningEffort;
        private final MujoongClock clock = new SystemMujoongClock();
        boolean useReasoning = true;
        boolean succeededOnce;

        TextProxy(Production production, Endpoint endpoint) {
            String env = System.getenv("OPENAI_ANALYSIS_MODEL");
            boolean fromEnv = env != null && !env.isBlank();
            model = fromEnv ? env.strip() : production.lunaModelDefault();
            modelSource = fromEnv ? "env OPENAI_ANALYSIS_MODEL" : "realtime application.yml mujoong.luna.model 기본값";
            if (!MODEL_NAME.matcher(model).matches()) {
                throw new IllegalStateException("텍스트 대리 모델 이름 형식이 올바르지 않다");
            }
            transport = new LunaTransport(endpoint.baseUrl(), endpoint.mode(), endpoint.apiKey(), production.maxResponseBytes());
            maxOutputTokens = production.maxOutputTokens();
            reasoningEffort = production.reasoningEffort();
        }

        TextReply generate(String instructions, List<Map<String, Object>> input) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("instructions", instructions);
            body.put("input", input);
            body.put("max_output_tokens", maxOutputTokens);
            if (useReasoning) {
                body.put("reasoning", Map.of("effort", reasoningEffort));
            }
            body.put("store", false);
            long started = clock.monotonicNanos();
            LunaTransport.RawResponse response;
            try {
                response = transport.send(JSON.write(body), TEXT_LIMIT).get(TEXT_LIMIT.plus(OUTER_MARGIN).toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException late) {
                return new TextReply("TIMEOUT", null, null, clock.elapsedSince(started).toMillis(), null, null);
            } catch (ExecutionException failed) {
                return new TextReply(reasonOf(failed), null, null, clock.elapsedSince(started).toMillis(), null, null);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("텍스트 대리 대기가 중단됐다", interrupted);
            }
            TextReply reply = classify(response, clock.elapsedSince(started).toMillis());
            succeededOnce |= reply.ok();
            return reply;
        }

        /** 첫 호출이 400(요청 거절)이고 추론 강도를 보냈다면, 추론 강도를 받지 않는 모델일 수 있어 한 번만 빼고 다시 보낸다. */
        boolean shouldRetryWithoutReasoning(TextReply reply) {
            if (!succeededOnce && useReasoning && "REQUEST_REJECTED".equals(reply.outcome())) {
                useReasoning = false;
                return true;
            }
            return false;
        }

        private static String reasonOf(Throwable failure) {
            Throwable cause = failure;
            while ((cause instanceof ExecutionException || cause instanceof java.util.concurrent.CompletionException)
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            return cause instanceof LunaException luna ? luna.reason().name() : "TRANSPORT_ERROR";
        }

        @SuppressWarnings("unchecked")
        private static TextReply classify(LunaTransport.RawResponse response, long latency) {
            Map<String, Object> root;
            try {
                root = JSON.read(response.body(), Map.class);
            } catch (RuntimeException notJson) {
                root = null;
            }
            Map<String, Long> usage = usageOf(root);
            int status = response.status();
            String failure = null;
            if (status == 401 || status == 403) {
                failure = "AUTH_FAILED";
            } else if (status == 429) {
                failure = "RATE_LIMITED";
            } else if (status >= 500) {
                failure = "SERVER_ERROR";
            } else if (status < 200 || status >= 300) {
                failure = "REQUEST_REJECTED";
            }
            if (failure != null) {
                return new TextReply(failure, null, null, latency, usage, errorCode(root));
            }
            if (root == null) {
                return new TextReply("SERVER_ERROR", null, null, latency, usage, null);
            }
            StringBuilder text = new StringBuilder();
            boolean refused = false;
            if (root.get("output") instanceof List<?> items) {
                for (Object item : items) {
                    if (!(item instanceof Map<?, ?> message) || !"message".equals(message.get("type"))
                            || !(message.get("content") instanceof List<?> contents)) {
                        continue;
                    }
                    for (Object content : contents) {
                        if (content instanceof Map<?, ?> part) {
                            if ("refusal".equals(part.get("type"))) {
                                refused = true;
                            } else if ("output_text".equals(part.get("type")) && part.get("text") instanceof String piece) {
                                text.append(piece);
                            }
                        }
                    }
                }
            }
            String model = root.get("model") instanceof String m && SAFE_TOKEN.matcher(m).matches() ? m : null;
            Object replyStatus = root.get("status");
            if ("incomplete".equals(replyStatus)) {
                return new TextReply("LENGTH_EXCEEDED", model, null, latency, usage, null);
            }
            if (refused) {
                return new TextReply("REFUSED", model, null, latency, usage, null);
            }
            if (!"completed".equals(replyStatus)) {
                return new TextReply("SERVER_ERROR", model, null, latency, usage, null);
            }
            if (text.toString().isBlank()) {
                return new TextReply("EMPTY_OUTPUT", model, null, latency, usage, null);
            }
            return new TextReply("OK", model, text.toString().strip(), latency, usage, null);
        }

        /** 오류 본문의 {@code error.code} · {@code error.type}(짧은 토큰만, 메시지 문장은 남기지 않는다). */
        private static String errorCode(Map<String, Object> root) {
            if (root == null || !(root.get("error") instanceof Map<?, ?> error)) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            for (String key : List.of("code", "type")) {
                if (error.get(key) instanceof String token && SAFE_TOKEN.matcher(token).matches()) {
                    parts.add(key + "=" + token);
                }
            }
            return parts.isEmpty() ? null : String.join(" ", parts);
        }

        @Override
        public void close() {
            transport.close();
        }
    }

    /** 응답 본문 {@code usage}의 숫자 필드(한 단계 안 세부 항목은 {@code 바깥.안}). 없으면 null. */
    static Map<String, Long> usageOf(Map<String, Object> root) {
        if (root == null || !(root.get("usage") instanceof Map<?, ?> usage)) {
            return null;
        }
        Map<String, Long> numbers = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : usage.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!SAFE_TOKEN.matcher(name).matches()) {
                continue;
            }
            if (entry.getValue() instanceof Number number) {
                numbers.put(name, number.longValue());
            } else if (entry.getValue() instanceof Map<?, ?> details) {
                for (Map.Entry<?, ?> detail : details.entrySet()) {
                    if (detail.getValue() instanceof Number number && SAFE_TOKEN.matcher(String.valueOf(detail.getKey())).matches()) {
                        numbers.put(name + "." + detail.getKey(), number.longValue());
                    }
                }
            }
        }
        return numbers;
    }

    // ------------------------------------------------------------------ 입력

    /** 진행할 시작 패키지 하나. {@code json}은 첫 목표만 남긴 practice 조각이다. */
    record GoalInput(String key, String file, String source, String json, Map<String, Object> rawGoal, int goalsInPackage) {
    }

    // ------------------------------------------------------------------ 하니스

    /** 조립 · 실행 · 기록. */
    static final class Harness {

        record Outcome(int runs, int completedRuns, int answeredJudgments, int judgmentCalls, int textCalls, int cap, String evidence,
                Path file) {
            String consoleLine() {
                return "PracticeDialogueLiveTest: 대화 " + runs + "건(끝까지 진행 " + completedRuns + "건), 판정 호출 " + judgmentCalls
                        + "회 · 텍스트 대리 호출 " + textCalls + "회(상한 " + cap + "), 증거=" + evidence + ", 파일=" + file;
            }
        }

        final Path out;
        private final Endpoint override;
        Endpoint endpoint;
        final Map<String, Object> summary = new LinkedHashMap<>();
        final MujoongClock wallClock = new SystemMujoongClock();
        Production production;
        CallBudget budget;
        Judge judge;
        TextProxy proxy;
        Map<String, String> approvedSpeech = Map.of();
        String sessionInstructions;
        final ProviderRequestFactory factory = new ProviderRequestFactory();

        Harness(Path out) {
            this(out, null);
        }

        /** {@code override}는 하니스 배선 점검용 가짜 공급자 주소다. 기본 실행은 null(운영 주소 · STRICT · env 키)이다. */
        Harness(Path out, Endpoint override) {
            this.out = out;
            this.override = override;
        }

        Outcome run() throws IOException {
            Files.createDirectories(out);
            movePreviousResult(out);
            summary.put("harness", "PracticeDialogueLiveTest(realtime)");
            summary.put("outcome", "RUNNING");
            summary.put("evidence", override == null ? EVIDENCE : override.evidence());
            summary.put("startedAt", wallClock.now().toString());
            summary.put("notes", notes());
            List<Map<String, Object>> goals = new ArrayList<>();
            List<Map<String, Object>> overview = new ArrayList<>();
            try {
                production = Production.load();
                summary.put("productionSettings", production.report());
                endpoint = override != null ? override : Endpoint.production(production);
                Optional<LunaHosts.Problem> hostProblem = LunaHosts.check(endpoint.baseUrl(), endpoint.mode());
                if ((override == null && endpoint.mode() != LunaHosts.Mode.STRICT) || hostProblem.isPresent()) {
                    throw new IllegalStateException("Luna 주소가 주소 규칙을 통과하지 못했다: " + hostProblem.map(Enum::name).orElse("MODE"));
                }
                List<GoalInput> inputs = new ArrayList<>();
                summary.put("source", loadInputs(inputs));
                budget = new CallBudget(callCap());
                summary.put("callBudget", ordered("cap", budget.cap, "rule", "전체 " + TOTAL_CALL_BUDGET
                        + "회 − core 하니스 호출 수(요약에서 읽음, 못 읽으면 core 최악 " + CORE_WORST_CASE_CALLS + "회), 최대 " + REALTIME_CALL_MAX
                        + "회. 반응 하나를 시작하기 전에 그 반응의 보통 호출 수만큼 남아 있어야 하고, 모자라면 SKIPPED_CALL_CAP"));
                approvedSpeech = loadApprovedSpeech();
                sessionInstructions = sessionInstructions();
                judge = new Judge(production, budget, endpoint);
                proxy = new TextProxy(production, endpoint);
                summary.put("models", models());
                summary.put("syntheticChild", ordered("displayName", SYNTHETIC_NAME, "ageYears", SYNTHETIC_AGE, "interests",
                        SYNTHETIC_INTERESTS, "judgmentAliasRule", SYNTHETIC_NAME + " → " + CHILD_ALIAS, "kind", "합성 프로필(실제 아동 아님)"));
                summary.put("reactionTypes", reactionTypes());
                summary.put("phaseLabels", phaseLabels());
                summary.put("overview", overview);
                summary.put("goals", goals);
                for (GoalInput input : inputs) {
                    goals.add(runGoal(input, overview));
                }
                summary.put("outcome", "COMPLETED");
            } catch (RuntimeException | IOException failure) {
                // 중간에 끊겨도 그때까지 모은 기록은 남긴다(원인은 예외 종류만)
                summary.put("outcome", "ABORTED");
                summary.put("aborted", failure.getClass().getSimpleName());
                throw failure;
            } finally {
                summary.put("finishedAt", wallClock.now().toString());
                summary.put("totals", totals(goals));
                writePretty(out.resolve(OUTPUT_FILE), summary);
                closeQuietly(judge);
                closeQuietly(proxy);
            }
            int runs = 0;
            int completed = 0;
            int answered = 0;
            for (Map<String, Object> goal : goals) {
                for (Map<String, Object> run : runsOf(goal)) {
                    if (!"SKIPPED_CALL_CAP".equals(run.get("status"))) {
                        runs++;
                    }
                    if ("COMPLETED".equals(run.get("status"))) {
                        completed++;
                    }
                    answered += ((Number) run.getOrDefault("answeredJudgments", 0)).intValue();
                }
            }
            return new Outcome(runs, completed, answered, budget == null ? 0 : budget.judgment, budget == null ? 0 : budget.text,
                    budget == null ? 0 : budget.cap, String.valueOf(summary.get("evidence")), out.resolve(OUTPUT_FILE));
        }

        private int coreCalls = -1;
        private boolean fromCore;

        private int callCap() {
            int core = coreCalls >= 0 ? coreCalls : fromCore ? CORE_WORST_CASE_CALLS : 0;
            return Math.max(0, Math.min(REALTIME_CALL_MAX, TOTAL_CALL_BUDGET - core));
        }

        // ---- 입력 ----

        /** core 하니스 패키지(요약 순서 → 나머지 이름 순), 없으면 공유 고정 자료 하나. */
        @SuppressWarnings("unchecked")
        private Map<String, Object> loadInputs(List<GoalInput> inputs) throws IOException {
            Map<String, Object> source = new LinkedHashMap<>();
            Path dir = out.resolve(PACKAGE_DIR);
            Path coreSummary = out.resolve(CORE_SUMMARY_FILE);
            List<Path> files = new ArrayList<>();
            Map<String, Object> core = null;
            if (Files.isRegularFile(coreSummary)) {
                try {
                    core = JSON.read(Files.readString(coreSummary, StandardCharsets.UTF_8), Map.class);
                } catch (RuntimeException unreadable) {
                    source.put("coreSummaryReadError", unreadable.getClass().getSimpleName());
                }
            }
            if (core != null) {
                source.put("coreSummaryOutcome", core.containsKey("outcome") ? core.get("outcome") : "REAL_RUN");
                if (core.get("totals") instanceof Map<?, ?> totals && totals.get("lunaCalls") instanceof Number calls) {
                    coreCalls = calls.intValue();
                    source.put("coreLunaCalls", coreCalls);
                }
                if (core.get("goals") instanceof List<?> rows) {
                    for (Object row : rows) {
                        if (row instanceof Map<?, ?> goal && goal.get("startPackageFile") instanceof String relative) {
                            Path file = out.resolve(relative).normalize();
                            if (file.startsWith(dir) && Files.isRegularFile(file)) {
                                files.add(file);
                            }
                        }
                    }
                }
            }
            if (Files.isDirectory(dir)) {
                try (Stream<Path> listed = Files.list(dir)) {
                    listed.filter(path -> path.getFileName().toString().endsWith(".json")).sorted()
                            .filter(path -> !files.contains(path)).forEach(files::add);
                }
            }
            if (!files.isEmpty()) {
                fromCore = true;
                source.put("kind", "CORE_HARNESS_START_PACKAGES");
                source.put("directory", PACKAGE_DIR + "/");
                if (core == null || core.containsKey("outcome")) {
                    source.put("warning", "core 요약이 이번 실호출 결과가 아니다(없거나 SKIPPED). 패키지가 지난 실호출 것일 수 있다");
                }
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    inputs.add(input(name.substring(0, name.length() - ".json".length()), PACKAGE_DIR + "/" + name,
                            "CORE_HARNESS", Files.readString(file, StandardCharsets.UTF_8)));
                }
            } else {
                source.put("kind", "SHARED_FIXTURE");
                source.put("warning", "core 하니스 결과(" + PACKAGE_DIR + "/)가 없어 공유 고정 자료 하나로 진행했다. 이 대본은 가짜 모델이 만든 고정 자료다");
                inputs.add(input("shared-fixture", FIXTURE, "SHARED_FIXTURE",
                        Files.readString(repoRoot().resolve(FIXTURE), StandardCharsets.UTF_8)));
            }
            source.put("files", inputs.stream().map(GoalInput::file).toList());
            source.put("goalRule", "시작 패키지마다 첫 목표(대본이 실린 목표)만 진행한다. 다음 목표 대본은 core 내부 호출로 받는데 이 모의에는 core가 없다");
            return source;
        }

        @SuppressWarnings("unchecked")
        private static GoalInput input(String key, String file, String source, String json) {
            Map<String, Object> raw = JSON.read(json, Map.class);
            List<Object> goals = raw.get("goals") instanceof List<?> list ? (List<Object>) list : List.of();
            Map<String, Object> first = goals.isEmpty() || !(goals.getFirst() instanceof Map<?, ?>) ? Map.of()
                    : (Map<String, Object>) goals.getFirst();
            Map<String, Object> trimmed = new LinkedHashMap<>(raw);
            trimmed.put("goals", first.isEmpty() ? List.of() : List.of(first));
            return new GoalInput(key, file, source, JSON.write(trimmed), first, goals.size());
        }

        /** 시작 패키지 approvedSpeech에 운영이 싣는 역할 전환 · 복귀 승인 문장(시드 SQL의 APPROVED 최고 판). 못 읽으면 비우고 생성형 알림으로 간다. */
        private Map<String, String> loadApprovedSpeech() {
            Map<String, String> speech = new LinkedHashMap<>();
            Map<String, Integer> versions = new LinkedHashMap<>();
            try {
                String sql = Files.readString(repoRoot().resolve(APPROVED_SPEECH_SEED), StandardCharsets.UTF_8);
                Matcher matcher = Pattern.compile("'(COACH_SWITCH|PEER_RETURN)',\\s*(\\d+),\\s*'([^']+)'").matcher(sql);
                while (matcher.find()) {
                    int version = Integer.parseInt(matcher.group(2));
                    if (version > versions.getOrDefault(matcher.group(1), 0)) {
                        versions.put(matcher.group(1), version);
                        speech.put(matcher.group(1), matcher.group(3));
                    }
                }
            } catch (IOException | RuntimeException unreadable) {
                speech.clear();
            }
            summary.put("approvedSpeech", ordered("texts", speech, "source", APPROVED_SPEECH_SEED + "(운영 시작 패키지 approvedSpeech와 같은 시드)",
                    "rule", "있으면 역할 전환 · 복귀 알림을 승인 문장으로 내고(모델 호출 없음), 없으면 생성형 알림으로 낸다"));
            return Map.copyOf(speech);
        }

        /**
         * GPT-Live 연습 세션 시작 지시문: 공통 역할 문장(아이 정보는 따옴표 안 데이터) + 연습 활동 본문. 운영 조립 규칙(역할 문장 + 공백 + 본문)이 자유대화
         * 공개 경로({@link ProviderRequestFactory#sessionStart(ChildContext)})와 같은지 먼저 대조한다.
         */
        private String sessionInstructions() {
            LiveSessionInstructions live = factory.liveInstructions();
            String role = DialogInstructions.role(SYNTHETIC_NAME, SYNTHETIC_AGE, SYNTHETIC_INTERESTS);
            String freeTalk = factory.sessionStart(new ChildContext(SYNTHETIC_NAME, SYNTHETIC_AGE, SYNTHETIC_INTERESTS)).session()
                    .instructions();
            boolean sameRule = freeTalk.equals(role + " " + live.of(ActivityType.FREE_TALK).text());
            LiveSessionInstructions.Body practice = live.of(ActivityType.PRACTICE);
            String text = role + " " + practice.text();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("version", practice.version());
            report.put("sha256", practice.sha256());
            report.put("text", text);
            report.put("compositionMatchesFreeTalkPath", sameRule);
            report.put("textProxyNote", TEXT_PROXY_NOTE);
            report.put("textProxyInstructions", "세션 지시문 text + 빈 줄 + textProxyNote(하니스 메모는 운영에 없다)");
            summary.put("sessionInstructions", report);
            return text;
        }

        private Map<String, Object> models() {
            // 주소 값은 쓰지 않고 주소 규칙만 적는다
            String rule = endpoint.mode() == LunaHosts.Mode.STRICT ? "STRICT(https + api.openai.com)" : endpoint.mode().name();
            Map<String, Object> models = new LinkedHashMap<>();
            models.put("judgment", ordered("requestedModel", judge.properties.model(), "modelSource",
                    "env OPENAI_JUDGE_MODEL(운영과 같은 이름, 비면 운영 기본 모델)", "purpose", "JUDGMENT_LIVE", "promptVersion",
                    judge.prompt.version(), "optionSetVersion", judge.optionSet.version(), "baseUrlRule", rule,
                    "attemptLimit", JUDGE_LIMIT.toString() + "(하니스 전용, 운영 live-budget " + production.liveBudget() + "과 비교해 적음)"));
            models.put("textProxy", ordered("requestedModel", proxy.model, "modelSource", proxy.modelSource, "api",
                    "Responses API 텍스트 출력(realtime LunaTransport, " + rule + ")", "maxOutputTokens", production.maxOutputTokens(),
                    "reasoningEffort", production.reasoningEffort() + "(realtime judgment-live 설정을 그대로 씀, 400이면 한 번만 빼고 다시 보냄)",
                    "attemptLimit", TEXT_LIMIT.toString(), "meaning", "GPT-Live 음성이 아니라 같은 지시문을 받은 텍스트 모델의 글 발화(텍스트 대리)"));
            return models;
        }

        // ---- 목표 · 대화 ----

        @SuppressWarnings("unchecked")
        private Map<String, Object> runGoal(GoalInput input, List<Map<String, Object>> overview) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", input.key());
            row.put("startPackageFile", input.file());
            row.put("source", input.source());
            row.put("goalsInPackage", input.goalsInPackage());
            Map<String, Object> raw = input.rawGoal();
            Map<String, Object> context = raw.get("goalContext") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            Map<String, Object> elements = context.get("fourElements") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            Map<String, Object> goal = new LinkedHashMap<>();
            goal.put("displayName", raw.get("displayName"));
            goal.put("judgmentCriteria", raw.get("judgmentCriteria"));
            goal.put("goalKind", raw.get("goalKind"));
            goal.put("targetExpression", context.get("targetExpression"));
            goal.put("fourElements", elements);
            goal.put("skillRef", context.get("skillRef"));
            row.put("goal", goal);
            List<Map<String, Object>> runs = new ArrayList<>();
            row.put("runs", runs);
            PracticeStartPackage pack;
            try {
                pack = PracticeStartPackage.parse(input.json());
            } catch (RuntimeException unreadable) {
                row.put("result", "BAD_PACKAGE");
                row.put("failure", unreadable.getClass().getSimpleName());
                return row;
            }
            PracticeScript script = pack.firstScenario();
            if (script == null) {
                row.put("result", "NO_NEW_FLOW_SCRIPT");
                row.put("scriptVersion", pack.scriptVersion());
                row.put("rejection", pack.firstScenarioRejection() == null ? null : pack.firstScenarioRejection().name());
                return row;
            }
            String target = context.get("targetExpression") instanceof String text ? text : null;
            String expression = elements.get("expression") instanceof String text ? text : null;
            Map<SceneKind, SceneLines> lines = new LinkedHashMap<>();
            List<Map<String, Object>> scenes = new ArrayList<>();
            for (Scene scene : script.scenes()) {
                SceneLines sceneLines = Child.lines(scene, target, expression);
                lines.putIfAbsent(scene.sceneKind(), sceneLines);
                scenes.add(sceneReport(scene, sceneLines));
            }
            Map<String, Object> scenario = new LinkedHashMap<>();
            scenario.put("scenarioId", script.scenarioId().toString());
            scenario.put("scriptVersion", script.scriptVersion());
            scenario.put("selfReview", plain(script.selfReview()));
            scenario.put("generation", plain(script.generation()));
            scenario.put("scenes", scenes);
            row.put("scenario", scenario);
            row.put("result", "RAN");
            for (Reaction reaction : Reaction.values()) {
                Map<String, Object> run;
                if (budget.remaining() < reaction.reserve) {
                    run = new LinkedHashMap<>();
                    run.put("reaction", reaction.name());
                    run.put("reactionLabel", reaction.label);
                    run.put("status", "SKIPPED_CALL_CAP");
                    run.put("remainingCalls", budget.remaining());
                    run.put("reserveNeeded", reaction.reserve);
                } else {
                    run = new DialogueRun(this, input, script, lines, reaction).run();
                }
                runs.add(run);
                overview.add(overviewRow(input.key(), String.valueOf(raw.get("displayName")), run));
            }
            return row;
        }

        private static Map<String, Object> sceneReport(Scene scene, SceneLines lines) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sceneKind", scene.sceneKind().name());
            map.put("sceneKey", scene.sceneKey());
            map.put("sceneDescription", scene.sceneDescription());
            map.put("opportunitySentence", scene.opportunitySentence());
            map.put("introEndsWithOpportunity", SceneIntro.carriesOpportunity(scene.sceneDescription(), scene.opportunitySentence()));
            map.put("reexplanation", scene.reexplanation());
            map.put("focusPoints", scene.focusPoints());
            map.put("expressionExamples", scene.expressionExamples());
            map.put("partnerRole", scene.partnerRole());
            map.put("peerNameInInstructions", RolePolicy.peerName(scene.partnerRole()));
            map.put("relation", scene.relation());
            map.put("place", scene.place());
            map.put("topic", scene.topic());
            map.put("childLines", lines.report());
            return map;
        }

        private static Map<String, Object> overviewRow(String key, String goalName, Map<String, Object> run) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("goal", key);
            map.put("goalName", goalName);
            map.put("reaction", run.get("reaction"));
            map.put("status", run.get("status"));
            map.put("judgmentTrail", run.getOrDefault("judgmentTrail", List.of()));
            map.put("sceneEnds", run.getOrDefault("sceneEnds", List.of()));
            map.put("helpProvided", run.getOrDefault("helpProvided", false));
            map.put("exampleGiven", run.getOrDefault("exampleGiven", false));
            return map;
        }

        @SuppressWarnings("unchecked")
        private static List<Map<String, Object>> runsOf(Map<String, Object> goal) {
            return goal.get("runs") instanceof List<?> runs ? (List<Map<String, Object>>) runs : List.of();
        }

        private Map<String, Object> totals(List<Map<String, Object>> goals) {
            Map<String, Object> totals = new LinkedHashMap<>();
            int runs = 0;
            int completed = 0;
            int skipped = 0;
            int answered = 0;
            int withinBudget = 0;
            for (Map<String, Object> goal : goals) {
                for (Map<String, Object> run : runsOf(goal)) {
                    if ("SKIPPED_CALL_CAP".equals(run.get("status"))) {
                        skipped++;
                        continue;
                    }
                    runs++;
                    if ("COMPLETED".equals(run.get("status"))) {
                        completed++;
                    }
                    answered += ((Number) run.getOrDefault("answeredJudgments", 0)).intValue();
                    withinBudget += ((Number) run.getOrDefault("judgmentsWithinProductionLiveBudget", 0)).intValue();
                }
            }
            totals.put("goals", goals.size());
            totals.put("runs", runs);
            totals.put("completedRuns", completed);
            totals.put("skippedByCallCap", skipped);
            totals.put("judgmentCalls", budget == null ? 0 : budget.judgment);
            totals.put("textProxyCalls", budget == null ? 0 : budget.text);
            totals.put("openAiCalls", budget == null ? 0 : budget.used());
            totals.put("callCap", budget == null ? 0 : budget.cap);
            totals.put("answeredJudgments", answered);
            totals.put("judgmentsWithinProductionLiveBudget", withinBudget);
            return totals;
        }

        private static List<String> notes() {
            return List.of(
                    "증거 종류는 REAL_API_SYNTHETIC(실제 API · 합성 자료)다. 테스트 대역 · 실제 기기 · 실제 GPT-Live 음성 성공이 아니다(R-M11).",
                    "상태 기계는 운영 PracticeConversation 그대로다. 출력 능력(재생 · 생성형 발화 · 판정 요청 · 시계 · 비동기 결과)만 하니스가 실행부 대신 받는다.",
                    "판정(J2 ~ J5)은 운영 조립 그대로다: 실행부와 같은 JudgeRequest(판 순서 선택지, 최근 아이 확정 전사 최대 3개 — 운영처럼 아이 말만, "
                            + "J2 · J3 · J5만 기준, 별칭 합성아이 → 아이) → LunaJudgeClient → RealtimeLunaClient → JudgeOutcomeValidator(min-confidence · "
                            + "min-margin은 application.yml). 확정이면 JUDGMENT_RESULT, 보류면 JUDGMENT_ABSTAINED로 흐름에 넘긴다.",
                    "판정 시도 시한만 넉넉히(" + JUDGE_LIMIT + ") 주고, 판정마다 운영 live-budget 안이었는지(withinProductionLiveBudget)를 적는다. 운영에서는 "
                            + "live-budget을 넘으면 TIMEOUT 보류다.",
                    "친구 · 코치 생성형 발화는 텍스트 대리다(textProxy: true). GPT-Live에 갈 세션 지시문을 instructions로, 지금까지의 대화를 input으로"
                            + "(승인 발화 읽기 지시 · 생성 지시는 developer, 무중의 말은 assistant, 아이 말은 user), 이번 지시(운영처럼 범위를 닫은 문장)를 "
                            + "마지막 developer로 보낸다. 텍스트 대리에만 하니스 메모 한 줄을 덧붙였다(sessionInstructions.textProxyNote).",
                    "승인 발화(장면 설명 · 기회 문장 · 상황 다시 설명 · 역할 전환 알림)는 모델을 부르지 않고 승인 원문 그대로 적었다. 운영에서는 GPT-Live가 읽고 "
                            + "원문 일치를 확인한다.",
                    "이 모의에는 제브 가드레일 검사 · 끼어들기 · 출력 세대가 없다. 재생은 곧바로 끝까지 들린 것으로(COMPLETED) 넘긴다.",
                    "시계는 가상 시계다. 아이가 말하지 않을 때만 무응답 시계(no-response-wait)만큼 시간을 흘린다. 집중 한도(focus-max) · 목표 한도"
                            + "(goal-active-limit)는 이 모의에서 흐르지 않는다.",
                    "아이 반응 문장은 규칙으로 만든 합성 문장이다(랜덤 없음, reactionTypes). 반응 유형 이름은 하니스의 의도이고, 판정이 다르게 나오면 그것도 "
                            + "평가 자료다.",
                    "OpenAI 호출은 core 하니스와 합쳐 약 " + TOTAL_CALL_BUDGET + "회 안쪽이 되게 상한을 둔다(callBudget). 상한에 걸린 대화는 "
                            + "SKIPPED_CALL_CAP(시작 안 함) 또는 CUT_BY_CALL_CAP(도중에 끊김)이다.",
                    "키 · 요청 헤더 · 주소는 쓰지 않는다. 오류는 사유 코드와 공급자 error.code · error.type 토큰만 적는다.");
        }

        private static List<Map<String, Object>> reactionTypes() {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Reaction reaction : Reaction.values()) {
                list.add(ordered("code", reaction.name(), "label", reaction.label, "reserveCalls", reaction.reserve, "rule", reaction.rule));
            }
            return list;
        }

        private static Map<String, String> phaseLabels() {
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("SCENE_INTRO", "장면 도입(장면 설명 승인 발화)");
            labels.put("OPPORTUNITY", "기회 문장 · 첫 대답(J2)");
            labels.put("REASK", "친구 되묻기(J3)");
            labels.put("HELP_OFFER", "도움 제안(J4)");
            labels.put("FOCUS", "코치 도움");
            labels.put("RETRY", "다시 해 보기(J5)");
            labels.put("SCENE_WRAP", "장면 정리");
            labels.put("VARIANT_OFFER", "변형 제안(J4 재사용)");
            labels.put("NEXT_GOAL", "다음 목표");
            labels.put("CLOSING", "마무리");
            return labels;
        }

        private static void closeQuietly(AutoCloseable closeable) {
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 정리 실패가 결과 파일 쓰기를 막지 않게 한다(결과는 이미 썼다)
            }
        }
    }

    // ------------------------------------------------------------------ 대화 하나

    /**
     * 목표 하나 × 반응 하나의 대화. 실행부 대신 출력 능력을 받아 편지함(차례 큐)처럼 사건을 하나씩 흐름에 넘긴다: 재생 → 끝까지 재생 사실, 생성형 발화
     * → 텍스트 대리 → 재생 사실, 판정 요청 → 실제 Luna → 결과 · 보류 사건, 시계 → 만료 사건. 큐가 비고 흐름이 아이 말을 기다리면 반응 규칙대로
     * 아이가 말하거나 침묵한다.
     */
    static final class DialogueRun implements PracticeOutput {

        private static final String PLAYBACK_EVENT = "u5b-playback";
        private static final String NO_RESPONSE = PracticeConversation.TIMER_NO_RESPONSE;

        private final Harness harness;
        private final PracticeScript script;
        private final Map<SceneKind, SceneLines> lines;
        private final Reaction reaction;
        private final MutableClock clock = new MutableClock();
        private final UUID sessionId = UUID.randomUUID();
        private final TimelineRecorder recorder = new TimelineRecorder();
        private final PracticeConversation ext;
        private final PracticeContext context;
        private final Deque<Supplier<Signal>> queue = new ArrayDeque<>();
        private final Map<String, Instant> timers = new LinkedHashMap<>();
        private final List<Map<String, Object>> timeline = new ArrayList<>();
        private final List<Map<String, Object>> history = new ArrayList<>();
        private final Deque<Map.Entry<Integer, String>> recent = new ArrayDeque<>();
        private final Map<Integer, Map<String, Object>> childTurns = new LinkedHashMap<>();
        private final Map<UUID, Map<String, Object>> spokenLines = new LinkedHashMap<>();
        private final Set<UUID> cancelled = new HashSet<>();
        private final List<HelpRecord> pendingHelps = new ArrayList<>();
        private final Map<String, Integer> inputsPerPhase = new LinkedHashMap<>();
        private final List<String> judgmentTrail = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private final List<String> notices = new ArrayList<>();
        private int seq;
        private int childTurnCount;
        private boolean judgmentBusy;
        private boolean done;
        private boolean cutByCap;
        private String status = "RUNNING";
        private String endSignal;
        private int judgmentCalls;
        private int textCalls;
        private int answeredJudgments;
        private int withinLiveBudget;

        DialogueRun(Harness harness, GoalInput input, PracticeScript script, Map<SceneKind, SceneLines> lines, Reaction reaction) {
            this.harness = harness;
            this.script = script;
            this.lines = lines;
            this.reaction = reaction;
            // 다음 목표 대본 요청은 쓰지 않는다(패키지의 첫 목표 하나만 진행, 내부 호출 대상 core가 없다)
            ext = new PracticeConversation(sessionId, UUID.randomUUID(), clock, harness.production.settings(),
                    new PracticeScriptRequester(mock(InternalHttpClient.class), Runnable::run, harness.production.slotGuard()), recorder,
                    failure -> failures.add(code(failure)));
            context = new PracticeContext(sessionId, input.json(), Set.of(), false, clock.now(), harness.approvedSpeech);
        }

        Map<String, Object> run() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("reaction", reaction.name());
            result.put("reactionLabel", reaction.label);
            int before = harness.budget.used();
            try {
                handle(ext.onActive(context, this));
                int steps = 0;
                while (!done) {
                    if (++steps > MAX_STEPS) {
                        status = "STEP_GUARD";
                        break;
                    }
                    Supplier<Signal> next = queue.poll();
                    if (next != null) {
                        handle(next.get());
                    } else if (awaitingChild()) {
                        if (++childTurnCount > MAX_CHILD_TURNS) {
                            status = "CHILD_TURN_GUARD";
                            break;
                        }
                        childActs();
                    } else if (ext.state().phase() == Phase.CLOSING) {
                        status = "CLOSING_WITHOUT_SIGNAL";
                        break;
                    } else if (!fireEarliestTimer()) {
                        status = "STALLED";
                        break;
                    }
                }
                if (done) {
                    status = cutByCap ? "CUT_BY_CALL_CAP" : "COMPLETED";
                }
                ext.onStopping();
            } catch (RuntimeException failure) {
                // 대화 하나가 깨져도 다음 대화는 돈다(원인은 예외 종류만)
                status = "ERROR";
                result.put("error", failure.getClass().getSimpleName());
            }
            result.put("status", status);
            result.put("endSignal", endSignal);
            result.put("endPhase", ext.state().phase().name());
            result.put("calls", ordered("judgment", judgmentCalls, "textProxy", textCalls, "thisRun", harness.budget.used() - before,
                    "harnessTotalAfter", harness.budget.used()));
            result.put("answeredJudgments", answeredJudgments);
            result.put("judgmentsWithinProductionLiveBudget", withinLiveBudget);
            result.put("judgmentTrail", judgmentTrail);
            result.put("sceneEnds", sceneEnds());
            List<HelpRecord> helps = recorder.delegate.helps;
            result.put("helpProvided", helps.stream().anyMatch(help -> help.kind() == HelpKind.FOCUS_HELP));
            result.put("exampleGiven", helps.stream().anyMatch(HelpRecord::exampleGiven));
            result.put("situationReexplained", helps.stream().anyMatch(help -> help.kind() == HelpKind.SITUATION_EXPLAIN));
            result.put("helps", helps.stream().map(DialogueRun::helpReport).toList());
            result.put("turns", timeline);
            Object record;
            try {
                record = plain(ext.practiceRecord());
            } catch (RuntimeException failure) {
                record = Map.of("error", failure.getClass().getSimpleName());
            }
            result.put("record", Map.of("practice", record == null ? Map.of() : record));
            result.put("flowFailures", failures);
            if (!notices.isEmpty()) {
                result.put("harnessNotices", notices);
            }
            return result;
        }

        private void handle(Signal signal) {
            if (signal == Signal.LAST_GOAL) {
                done = true;
                endSignal = signal.name();
            } else if (signal == Signal.NEXT_GOAL) {
                // 목표 하나만 진행하므로 오지 않는다. 와도 기록만 남기고 잇는다
                endSignal = signal.name();
            }
        }

        private boolean awaitingChild() {
            FlowState state = ext.state();
            return state.phase().awaitsInput() && state.sub() == Sub.NONE && !state.judgmentOpen() && !judgmentBusy
                    && timers.containsKey(NO_RESPONSE);
        }

        private Scene currentScene() {
            return ext.state().sceneKind() == SceneKind.VARIANT ? script.variantScene().orElse(script.baseScene()) : script.baseScene();
        }

        // ---- 아이 ----

        private void childActs() {
            FlowState state = ext.state();
            Phase phase = state.phase();
            int nth = inputsPerPhase.merge(state.sceneKind() + ":" + phase, 1, Integer::sum);
            SceneLines sceneLines = lines.getOrDefault(state.sceneKind(), lines.get(SceneKind.BASE));
            ChildAct act = Child.act(reaction, phase, nth, sceneLines);
            Map<String, Object> turn = entry("CHILD");
            turn.put("text", act.text());
            turn.put("silence", act.silent());
            turn.put("rule", act.rule());
            if (act.silent()) {
                add(turn);
                Instant due = timers.remove(NO_RESPONSE);
                if (due.isAfter(clock.now())) {
                    clock.advance(Duration.between(clock.now(), due));
                }
                Map<String, Object> fired = flow("TIMER_FIRED");
                fired.put("timer", NO_RESPONSE);
                add(fired);
                handle(ext.onExtensionEvent(new ExtensionEvent(ExtensionEvent.Kind.TIMER_FIRED, NO_RESPONSE, null, null), this));
                return;
            }
            seq++;
            turn.put("seq", seq);
            add(turn);
            childTurns.put(seq, turn);
            recent.addLast(Map.entry(seq, act.text()));
            while (recent.size() > RECENT_LIMIT) {
                recent.removeFirst();
            }
            history.add(message("user", act.text()));
            handle(ext.onChildUtterance(new Utterance(seq, clock.now(), act.text(), false, false), this));
        }

        // ---- 출력 능력 ----

        @Override
        public UUID sessionId() {
            return sessionId;
        }

        @Override
        public boolean speak(PracticeSpeech speech) {
            Map<String, Object> line = entry("APPROVED_SPEECH");
            line.put("voice", speech.partnerRole());
            line.put("kind", approvedKind(speech.text()));
            line.put("text", speech.text());
            attachHelps(line);
            add(line);
            spokenLines.put(speech.correlationId(), line);
            history.add(message("developer", harness.factory.practiceInstruction("speech", PracticeInstruction.forSpeech(speech)).content()));
            history.add(message("assistant", speech.text()));
            queue.add(() -> playbackDone(speech.correlationId(), speech, Playback.COMPLETED, speech.text()));
            return true;
        }

        /** 실행부와 같다: 승인 문장이 있으면 승인 발화 재생(확장은 그 슬롯의 correlationId를 모른다), 없으면 거짓(확장이 생성형으로 낸다). */
        @Override
        public boolean announceRoleSwitch(String toRole) {
            String code = "COACH".equals(toRole) ? "COACH_SWITCH" : "PEER".equals(toRole) ? "PEER_RETURN" : null;
            String sentence = code == null ? null : harness.approvedSpeech.get(code);
            if (sentence == null) {
                return false;
            }
            PracticeSpeech speech;
            try {
                speech = PracticeSpeech.scenario(UUID.randomUUID(), script.scenarioId(), 0, "COACH".equals(toRole) ? "코치" : "친구", sentence);
            } catch (IllegalArgumentException invalid) {
                return false;
            }
            Map<String, Object> line = entry("APPROVED_SPEECH");
            line.put("voice", speech.partnerRole());
            line.put("kind", "ROLE_NOTICE_" + code);
            line.put("text", sentence);
            attachHelps(line);
            add(line);
            history.add(message("developer", harness.factory.practiceInstruction("notice", PracticeInstruction.forSpeech(speech)).content()));
            history.add(message("assistant", sentence));
            queue.add(() -> playbackDone(speech.correlationId(), speech, Playback.COMPLETED, sentence));
            return true;
        }

        @Override
        public boolean speakGenerated(UUID correlationId, String role, String instructionText) {
            PracticeInstruction instruction;
            try {
                instruction = new PracticeInstruction(instructionText);
            } catch (RuntimeException invalid) {
                // 실행부도 지시문 형식이 틀리면 거절한다(PRACTICE_EXTENSION_FAILED INVALID)
                Map<String, Object> refused = flow("GENERATED_SPEECH_REFUSED");
                refused.put("reason", "INVALID_INSTRUCTION");
                add(refused);
                return false;
            }
            if (!harness.budget.available()) {
                cutByCap = true;
                Map<String, Object> refused = flow("GENERATED_SPEECH_REFUSED");
                refused.put("reason", "HARNESS_CALL_CAP");
                add(refused);
                return false;
            }
            Map<String, Object> line = entry(role);
            line.put("textProxy", true);
            line.put("template", template(instructionText));
            line.put("instruction", instructionText);
            attachHelps(line);
            String scoped = harness.factory.practiceInstruction("generated", instruction).content();
            queue.add(() -> generate(correlationId, line, scoped));
            return true;
        }

        private Signal generate(UUID correlationId, Map<String, Object> line, String scoped) {
            if (cancelled.contains(correlationId)) {
                line.put("text", null);
                line.put("cancelledBeforeGeneration", true);
                add(line);
                return Signal.NONE;
            }
            List<Map<String, Object>> request = new ArrayList<>(history);
            request.add(message("developer", scoped));
            TextReply reply = callText(request);
            if (reply != null && harness.proxy.shouldRetryWithoutReasoning(reply)) {
                notices.add("텍스트 대리 첫 호출이 REQUEST_REJECTED여서 reasoning 필드를 빼고 한 번 다시 보냈다");
                TextReply retried = callText(request);
                reply = retried == null ? reply : retried;
            }
            Map<String, Object> generation = new LinkedHashMap<>();
            if (reply == null) {
                cutByCap = true;
                generation.put("outcome", "HARNESS_CALL_CAP");
            } else {
                generation.put("outcome", reply.outcome());
                generation.put("model", reply.model());
                generation.put("latencyMs", reply.latencyMillis());
                generation.put("usage", reply.usage());
                if (reply.errorCode() != null) {
                    generation.put("errorCode", reply.errorCode());
                }
            }
            boolean ok = reply != null && reply.ok();
            line.put("text", ok ? reply.text() : null);
            line.put("generation", generation);
            add(line);
            spokenLines.put(correlationId, line);
            history.add(message("developer", scoped));
            if (ok) {
                history.add(message("assistant", reply.text()));
            }
            return playbackDone(correlationId, null, ok ? Playback.COMPLETED : Playback.FAILED, ok ? reply.text() : "");
        }

        private TextReply callText(List<Map<String, Object>> request) {
            if (!harness.budget.available()) {
                return null;
            }
            harness.budget.text++;
            textCalls++;
            return harness.proxy.generate(harness.sessionInstructions + "\n\n" + TEXT_PROXY_NOTE, request);
        }

        private Signal playbackDone(UUID correlationId, PracticeSpeech speech, Playback status, String text) {
            return ext.onExtensionEvent(new ExtensionEvent(ExtensionEvent.Kind.PLAYBACK_FACT, PLAYBACK_EVENT, correlationId,
                    new PlaybackFact(speech, status, text)), this);
        }

        /**
         * 실행부 {@code requestExtensionJudgment} · {@code JudgmentCoordinator.open}과 같은 거절 조건과 같은 요청 조립. 결과는 다음 차례에 실제 Luna로
         * 받아 확정이면 JUDGMENT_RESULT, 보류면 JUDGMENT_ABSTAINED로 넘긴다.
         */
        @Override
        public boolean requestJudgment(JudgmentPoint point, long transcriptSeq, UUID correlationId, Map<String, Object> criteria) {
            Map<String, Object> turn = childTurns.get((int) Math.min(Integer.MAX_VALUE, Math.max(0, transcriptSeq)));
            Map<String, Object> judgment = new LinkedHashMap<>();
            judgment.put("point", point == null ? null : point.name());
            if (turn != null) {
                turn.put("judgment", judgment);
            }
            if (point == null || correlationId == null || point == JudgmentPoint.J1 || point == JudgmentPoint.J6 || transcriptSeq < 0
                    || transcriptSeq > Integer.MAX_VALUE) {
                return refuseJudgment(judgment, "OUT_OF_RANGE");
            }
            Map.Entry<Integer, String> target = recent.stream().filter(item -> item.getKey() == transcriptSeq).findFirst().orElse(null);
            if (target == null) {
                return refuseJudgment(judgment, "NOT_RECENT");
            }
            JudgeRequest.Criteria shaped = null;
            if (point == JudgmentPoint.J2 || point == JudgmentPoint.J3 || point == JudgmentPoint.J5) {
                try {
                    shaped = new JudgeRequest.Criteria((String) (criteria == null ? null : criteria.get("coreFunction")),
                            (String) (criteria == null ? null : criteria.get("judgmentCriteria")));
                } catch (RuntimeException invalid) {
                    return refuseJudgment(judgment, "BAD_CRITERIA");
                }
            }
            if (judgmentBusy) {
                return refuseJudgment(judgment, "BUSY");
            }
            if (!harness.judge.available()) {
                cutByCap = true;
                return refuseJudgment(judgment, "HARNESS_CALL_CAP");
            }
            String text = clip(target.getValue());
            List<JudgeRequest.RecentTurn> before = recentBefore(target.getKey());
            LiveContentResources.PointOptions options = harness.judge.optionSet.points()
                    .get(LiveContentResources.JudgmentPoint.valueOf(point.name()));
            JudgeRequest request = new JudgeRequest(point, harness.judge.optionSet.version(), options.optionCodes(), text, before, shaped,
                    Map.of(SYNTHETIC_NAME, CHILD_ALIAS), false, harness.wallClock.now().plus(JUDGE_LIMIT));
            judgment.put("requested", true);
            judgment.put("options", options.optionCodes());
            judgment.put("criteriaSent", shaped != null);
            judgment.put("recentChildTurnsSent", before.stream().map(JudgeRequest.RecentTurn::text).toList());
            judgmentBusy = true;
            queue.add(() -> deliverJudgment(request, correlationId, judgment));
            return true;
        }

        private boolean refuseJudgment(Map<String, Object> judgment, String reason) {
            judgment.put("requested", false);
            judgment.put("refusedBy", reason);
            judgmentTrail.add(judgment.get("point") + ":요청 거절(" + reason + ")");
            return false;
        }

        private Signal deliverJudgment(JudgeRequest request, UUID correlationId, Map<String, Object> judgment) {
            JudgeOutcome outcome;
            if (!harness.judge.available()) {
                cutByCap = true;
                outcome = null;
            } else {
                judgmentCalls++;
                outcome = harness.judge.judge(request);
            }
            judgmentBusy = false;
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("point", request.point().name());
            ExtensionEvent.Kind kind;
            if (outcome == null) {
                kind = ExtensionEvent.Kind.JUDGMENT_ABSTAINED;
                value.put("abstainReason", "HARNESS_CALL_CAP");
                judgment.put("outcome", "NOT_CALLED_HARNESS_CALL_CAP");
            } else {
                judgment.put("outcome", outcome.kind().name());
                judgment.put("first", outcome.firstOption());
                judgment.put("firstProbability", outcome.firstProbability());
                judgment.put("second", outcome.secondOption());
                judgment.put("secondProbability", outcome.secondProbability());
                judgment.put("abstainReason", outcome.abstainReason() == null ? null : outcome.abstainReason().name());
                judgment.put("model", outcome.model());
                judgment.put("promptVersion", outcome.promptVersion());
                judgment.put("latencyMs", outcome.latency().toMillis());
                boolean within = outcome.latency().compareTo(harness.production.liveBudget()) <= 0;
                judgment.put("withinProductionLiveBudget", within);
                if (outcome.model() != null) {
                    answeredJudgments++;
                    if (within) {
                        withinLiveBudget++;
                    }
                }
                if (outcome.isConfirmed()) {
                    kind = ExtensionEvent.Kind.JUDGMENT_RESULT;
                    value.put("firstOption", outcome.firstOption());
                    value.put("secondOption", outcome.secondOption());
                } else {
                    kind = ExtensionEvent.Kind.JUDGMENT_ABSTAINED;
                    value.put("abstainReason", outcome.abstainReason() == null ? "FAULT" : outcome.abstainReason().name());
                    // 실행부(SessionExecutor.afterExtensionJudgment)와 같이 문턱 미달 보류에만 확정 안 된 1순위 코드를 싣는다
                    if (outcome.firstOption() != null) {
                        value.put("leadingOption", outcome.firstOption());
                    }
                }
            }
            judgment.put("deliveredToFlow", kind.name());
            judgmentTrail.add(trail(request.point(), judgment));
            Signal signal = ext.onExtensionEvent(new ExtensionEvent(kind, "judgment", correlationId, value), this);
            judgment.put("phaseAfter", ext.state().phase().name());
            judgment.put("subAfter", ext.state().sub().name());
            return signal;
        }

        private static String trail(JudgmentPoint point, Map<String, Object> judgment) {
            Object first = judgment.get("first");
            String probability = judgment.get("firstProbability") instanceof Number number
                    ? String.format(Locale.ROOT, " %.2f", number.doubleValue()) : "";
            if ("CONFIRMED".equals(judgment.get("outcome"))) {
                return point + ":" + first + probability;
            }
            Object reason = judgment.get("abstainReason") != null ? judgment.get("abstainReason") : judgment.get("outcome");
            return point + ":보류(" + reason + (first == null ? "" : ", 1순위 " + first + probability) + ")";
        }

        /** 그 전사 바로 앞의 아이 전사 최대 3개(오래된 것부터, 실행부 {@code recentBefore}와 같다). */
        private List<JudgeRequest.RecentTurn> recentBefore(int targetSeq) {
            List<JudgeRequest.RecentTurn> before = new ArrayList<>();
            for (Map.Entry<Integer, String> item : recent) {
                if (item.getKey() < targetSeq) {
                    before.add(new JudgeRequest.RecentTurn(JudgeRequest.Speaker.CHILD, clip(item.getValue())));
                }
            }
            int from = Math.max(0, before.size() - JudgeRequest.MAX_RECENT_TURNS);
            return List.copyOf(before.subList(from, before.size()));
        }

        private static String clip(String text) {
            return text.length() <= JudgeRequest.MAX_TEXT_CHARS ? text : text.substring(0, JudgeRequest.MAX_TEXT_CHARS);
        }

        @Override
        public boolean schedule(String name, Duration delay) {
            timers.put(name, clock.now().plus(delay));
            return true;
        }

        @Override
        public boolean cancel(String name) {
            timers.remove(name);
            return true;
        }

        @Override
        public boolean cancelSpeech(UUID correlationId) {
            cancelled.add(correlationId);
            Map<String, Object> line = spokenLines.get(correlationId);
            if (line != null) {
                line.put("cancelledByFlow", true);
            }
            return true;
        }

        @Override
        public boolean holdOutput(boolean hold) {
            Map<String, Object> held = flow("HOLD_OUTPUT");
            held.put("hold", hold);
            add(held);
            return true;
        }

        @Override
        public boolean post(String name, Object value) {
            queue.add(() -> ext.onExtensionEvent(new ExtensionEvent(ExtensionEvent.Kind.ASYNC_RESULT, name, null, value), this));
            return true;
        }

        @Override
        public boolean setThinking(boolean on) {
            return true;
        }

        @Override
        public boolean playApproved(String contentCode) {
            return unexpectedCapability("playApproved");
        }

        @Override
        public boolean instructProvider(String text) {
            return unexpectedCapability("instructProvider");
        }

        @Override
        public boolean requestResponse() {
            return unexpectedCapability("requestResponse");
        }

        @Override
        public boolean cancelResponse() {
            return unexpectedCapability("cancelResponse");
        }

        /** 새 흐름은 레거시 능력을 쓰지 않는다. 쓰면 기록만 남기고 거절한다. */
        private boolean unexpectedCapability(String name) {
            Map<String, Object> used = flow("LEGACY_CAPABILITY_USED");
            used.put("capability", name);
            add(used);
            return false;
        }

        /** 큐가 비었는데 아이 차례도 아니면 가장 이른 확장점 시계를 터뜨린다(가상 시계를 그만큼 앞당긴다). */
        private boolean fireEarliestTimer() {
            Map.Entry<String, Instant> due = timers.entrySet().stream().min(Map.Entry.comparingByValue()).orElse(null);
            if (due == null) {
                return false;
            }
            timers.remove(due.getKey());
            if (due.getValue().isAfter(clock.now())) {
                clock.advance(Duration.between(clock.now(), due.getValue()));
            }
            Map<String, Object> fired = flow("TIMER_FIRED");
            fired.put("timer", due.getKey().startsWith(PracticeConversation.TIMER_SLOT_GUARD) ? PracticeConversation.TIMER_SLOT_GUARD + "*"
                    : due.getKey());
            add(fired);
            handle(ext.onExtensionEvent(new ExtensionEvent(ExtensionEvent.Kind.TIMER_FIRED, due.getKey(), null, null), this));
            return true;
        }

        // ---- 기록 ----

        private Map<String, Object> entry(String speaker) {
            FlowState state = ext.state();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("speaker", speaker);
            entry.put("phase", state.phase().name());
            entry.put("sub", state.sub().name());
            entry.put("sceneKind", state.sceneKind().name());
            entry.put("flowRole", state.role().name());
            return entry;
        }

        private Map<String, Object> flow(String event) {
            Map<String, Object> entry = entry("FLOW");
            entry.put("event", event);
            return entry;
        }

        private void add(Map<String, Object> entry) {
            Map<String, Object> numbered = new LinkedHashMap<>();
            numbered.put("n", timeline.size() + 1);
            numbered.putAll(entry);
            entry.clear();
            entry.putAll(numbered);
            timeline.add(entry);
        }

        /** 이 발화 직전에 남은 도움 기록(코치 도움 · 상황 다시 설명)을 붙인다. */
        private void attachHelps(Map<String, Object> line) {
            List<Map<String, Object>> helps = pendingHelps.stream()
                    .filter(help -> help.kind() == HelpKind.FOCUS_HELP || help.kind() == HelpKind.SITUATION_EXPLAIN
                            || help.kind() == HelpKind.EXPRESSION_EXAMPLE)
                    .map(DialogueRun::helpReport).toList();
            pendingHelps.clear();
            line.put("helpProvided", !helps.isEmpty());
            if (!helps.isEmpty()) {
                line.put("help", helps);
            }
        }

        private String approvedKind(String text) {
            Scene scene = currentScene();
            if (text.equals(scene.sceneDescription())) {
                return "SCENE_DESCRIPTION";
            }
            if (text.equals(scene.opportunitySentence())) {
                return "OPPORTUNITY_SENTENCE";
            }
            if (text.equals(scene.reexplanation())) {
                return "REEXPLANATION";
            }
            return "APPROVED_OTHER";
        }

        /** 지시문이 어느 템플릿에서 왔는지(지금 장면으로 템플릿을 다시 만들어 글자 그대로 맞춘다). */
        private String template(String instruction) {
            Scene scene = currentScene();
            Map<String, String> templates = new LinkedHashMap<>();
            templates.putIfAbsent(PracticeInstructions.peerReask(scene, false), "PEER_REASK(친구 되묻기)");
            templates.putIfAbsent(PracticeInstructions.peerReask(scene, true), "PEER_REASK_AGAIN(친구 되묻기 · 다시 안내)");
            templates.putIfAbsent(PracticeInstructions.peerHelpOffer(scene, false), "PEER_HELP_OFFER(친구 도움 제안)");
            templates.putIfAbsent(PracticeInstructions.peerHelpOffer(scene, true), "PEER_HELP_OFFER_AGAIN(친구 도움 제안 · 다시 안내)");
            templates.putIfAbsent(PracticeInstructions.peerVariantOffer(scene, false), "PEER_VARIANT_OFFER(친구 변형 제안)");
            templates.putIfAbsent(PracticeInstructions.peerVariantOffer(scene, true), "PEER_VARIANT_OFFER_AGAIN(친구 변형 제안 · 다시 안내)");
            templates.putIfAbsent(PracticeInstructions.coachHelp(scene, false), "COACH_HELP_CUE(코치 단서만)");
            templates.putIfAbsent(PracticeInstructions.coachHelp(scene, true), "COACH_HELP_EXAMPLE(코치 표현 예시 허용)");
            templates.putIfAbsent(PracticeInstructions.coachRetryPrompt(scene), "COACH_RETRY_PROMPT(코치 다시 해 보기 권유)");
            templates.putIfAbsent(PracticeInstructions.roleNotice("COACH", scene), "ROLE_NOTICE_COACH(코치 전환 알림 · 생성형)");
            templates.putIfAbsent(PracticeInstructions.roleNotice("PEER", scene), "ROLE_NOTICE_PEER(친구 복귀 알림 · 생성형)");
            return templates.getOrDefault(instruction, "UNKNOWN");
        }

        private List<String> sceneEnds() {
            List<String> ends = new ArrayList<>();
            for (SceneRunRecord row : recorder.delegate.list()) {
                ends.add(row.sceneKind() + " " + (row.liveTiming() == null ? "-" : row.liveTiming().name())
                        + (row.variantSkipReason() == null ? "" : " 변형생략=" + row.variantSkipReason().name()) + " 다시해보기="
                        + row.retryCount() + " 다시설명=" + row.reexplainCount());
            }
            return ends;
        }

        private static Map<String, Object> helpReport(HelpRecord help) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", help.kind().name());
            map.put("role", help.role().name());
            map.put("exampleGiven", help.exampleGiven());
            map.put("consent", help.consentAnswer() == null ? null : help.consentAnswer().name());
            map.put("contentChars", help.contentChars());
            return map;
        }

        private static Map<String, Object> message(String role, String content) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", role);
            message.put("content", content);
            return message;
        }

        /** 흐름이 알리는 앱 오류 한 줄은 고정 사유 코드만 남긴다(그 밖은 예외 종류). */
        private static String code(RuntimeException failure) {
            String message = failure.getMessage();
            return message != null && FIXED_CODE.matcher(message).matches() ? message : failure.getClass().getSimpleName();
        }

        /** 운영 기록 자리({@link MemoryRecorder}, 운영 조립기와 같은 출력)에 쌓으면서 대화 기록에도 흐름 사건으로 남긴다. */
        final class TimelineRecorder implements FlowRecorder {

            final MemoryRecorder delegate = new MemoryRecorder();

            @Override
            public void sceneStarted(SceneRunRecord started) {
                delegate.sceneStarted(started);
                Map<String, Object> entry = flow("SCENE_STARTED");
                entry.put("recordedSceneKind", started.sceneKind().name());
                add(entry);
            }

            @Override
            public void fact(UUID sceneRunId, FactKind kind, Instant at) {
                delegate.fact(sceneRunId, kind, at);
                Map<String, Object> entry = flow("FACT");
                entry.put("fact", kind.name());
                add(entry);
            }

            @Override
            public void help(HelpRecord help) {
                delegate.help(help);
                pendingHelps.add(help);
                Map<String, Object> entry = flow("HELP");
                entry.put("help", helpReport(help));
                add(entry);
            }

            @Override
            public void sceneOutcome(UUID sceneRunId, Instant endedAt, LiveTiming timing, VariantSkipReason skip, int retryCount,
                    int reexplainCount) {
                delegate.sceneOutcome(sceneRunId, endedAt, timing, skip, retryCount, reexplainCount);
                Map<String, Object> entry = flow("SCENE_OUTCOME");
                entry.put("liveTiming", timing == null ? null : timing.name());
                entry.put("variantSkipReason", skip == null ? null : skip.name());
                entry.put("retryCount", retryCount);
                entry.put("reexplainCount", reexplainCount);
                add(entry);
            }

            @Override
            public Object practiceRecord() {
                return delegate.practiceRecord();
            }
        }
    }

    // ------------------------------------------------------------------ 쓰기

    /** 순서를 지키는 작은 지도(키, 값, 키, 값 …). */
    static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    static Object plain(Object value) {
        return value == null ? null : JSON.read(JSON.write(value), Object.class);
    }

    static void writePretty(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, PRETTY.writeValueAsString(PRETTY.readTree(JSON.write(value))) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }
}
