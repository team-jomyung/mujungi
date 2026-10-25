package kr.mujoong.realtime.practiceflow;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.content.LiveContentRegistry;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.gate.SeedDictionary;
import kr.mujoong.realtime.guardrail.ApprovedVerbatimPort;
import kr.mujoong.realtime.guardrail.GuardrailClient;
import kr.mujoong.realtime.guardrail.GuardrailMode;
import kr.mujoong.realtime.guardrail.GuardrailProperties;
import kr.mujoong.realtime.guardrail.GuardrailStartupRules;
import kr.mujoong.realtime.guardrail.GuardrailSupport;
import kr.mujoong.realtime.guardrail.JevGuardrailClient;
import kr.mujoong.realtime.guardrail.JevHosts;
import kr.mujoong.realtime.guardrail.JevQuestion;
import kr.mujoong.realtime.guardrail.StubGuardrailClient;
import kr.mujoong.realtime.judgment.DecisionsJudgeClient;
import kr.mujoong.realtime.judgment.JudgeClient;
import kr.mujoong.realtime.judgment.JudgeOutcomeValidator;
import kr.mujoong.realtime.judgment.JudgmentPrompt;
import kr.mujoong.realtime.luna.LunaHosts;
import kr.mujoong.realtime.luna.LunaInputSanitizer;
import kr.mujoong.realtime.luna.LunaProperties;
import kr.mujoong.realtime.luna.LunaSchemas;
import kr.mujoong.realtime.luna.LunaTransport;
import kr.mujoong.realtime.luna.RealtimeLunaClient;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.practiceflow.PracticeVoiceChild.GoalLines;
import kr.mujoong.realtime.practiceflow.PracticeVoiceChild.Plan;
import kr.mujoong.realtime.voice.ProviderClient;
import kr.mujoong.realtime.voice.ProviderHosts;
import kr.mujoong.realtime.voice.ProviderPort;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 실음성 연습 하니스의 조립 · 실행 · 기록({@link PracticeVoiceDialogueLiveTest}). 운영 설정(application.yml + application-demo.yml)을 묶고,
 * core 하니스가 남긴 시작 패키지를 읽어 목표 × 아이 계획 대화를 상한 안에서 차례로 돌리고, 결과를 JSON과 Markdown으로 쓴다.
 */
final class PracticeVoiceHarness {

    // ---- 증거 종류(R-M11) ----
    static final String EVIDENCE_REAL_JEV = "REAL_API_GPT_LIVE_LUNA_JEV_SYNTHETIC_CHILD_VOICE";
    static final String EVIDENCE_STUB_JEV = "REAL_API_GPT_LIVE_LUNA_SYNTHETIC_CHILD_VOICE_JEV_STUB";
    static final String LABEL_REAL_JEV = "실제 API(GPT-Live · Luna · 제브) + 합성 아이 음성";
    static final String LABEL_STUB_JEV = "실제 API(GPT-Live · Luna) + 합성 아이 음성 + 제브 대역";

    // ---- env ----
    static final String TTS_MODEL_ENV = "MUJOONG_LIVE_TTS_MODEL";
    static final String TTS_VOICE_ENV = "MUJOONG_LIVE_TTS_VOICE";
    static final String DEFAULT_TTS_MODEL = "gpt-4o-mini-tts";
    static final String DEFAULT_TTS_VOICE = "nova";
    /** 아이 말 모델(없으면 운영 Luna 모델 — application.yml mujoong.luna.model). */
    static final String CHILD_MODEL_ENV = "MUJOONG_LIVE_CHILD_MODEL";
    static final String DIALOGUE_SECONDS_ENV = "MUJOONG_LIVE_VOICE_DIALOGUE_SECONDS";
    static final String TOTAL_SECONDS_ENV = "MUJOONG_LIVE_VOICE_TOTAL_SECONDS";
    static final String MAX_DIALOGUES_ENV = "MUJOONG_LIVE_VOICE_MAX_DIALOGUES";
    static final String TTS_CALLS_ENV = "MUJOONG_LIVE_VOICE_TTS_CALLS";
    static final String TEXT_CALLS_ENV = "MUJOONG_LIVE_VOICE_TEXT_CALLS";
    /** 돌릴 아이 계획(쉼표, 없으면 7가지 모두). */
    static final String PLANS_ENV = "MUJOONG_LIVE_VOICE_PLANS";
    /** 돌릴 목표(시작 패키지 파일 이름, 쉼표, 없으면 모두). */
    static final String GOALS_ENV = "MUJOONG_LIVE_VOICE_GOALS";
    static final String JEV_API_KEY_ENV = JevGuardrailClient.API_KEY_ENV;
    static final String JEV_BASE_URL_ENV = "JEV_BASE_URL";
    static final String JEV_MODEL_ENV = "JEV_MODEL";
    static final String JEV_PASS_THRESHOLD_ENV = "JEV_PASS_THRESHOLD";
    static final String JEV_SEND_CHILD_NAME_ENV = "JEV_SEND_CHILD_NAME";
    static final String JUDGE_MODEL_ENV = "OPENAI_JUDGE_MODEL";

    static final int DEFAULT_DIALOGUE_SECONDS = 300;
    static final int DEFAULT_TOTAL_SECONDS = 1800;
    static final int DEFAULT_MAX_DIALOGUES = 7;
    static final int DEFAULT_TTS_CALLS = 120;
    static final int DEFAULT_TEXT_CALLS = 200;

    // ---- 입력 ----
    static final String PACKAGE_DIR = "practice-start-packages";
    static final String CORE_SUMMARY_FILE = "core-practice-packages.json";
    static final String APPROVAL_FILE = "infra/approved-audio/approval-20261021.json";
    static final String SPOKEN_SEED = "infra/seed/005-spoken-audio-reference.sql";
    static final List<String> SPEECH_SEEDS = List.of("infra/seed/004-u8-reference.sql", "infra/seed/006-practice-direct-ask-reference.sql");
    /** 연습 시작 패키지 approvedContent(core StartActivityService: CLOSING · WAITING_FILLER · 자유대화 공용 문구 · INTRO_PRACTICE). */
    static final List<String> PRACTICE_CONTENT = List.of("CLOSING", "WAITING_FILLER", "RE_PROMPT", "DECLINE_ALTERNATIVE", "SILENCE_WARNING",
            "STOP_CONFIRM", "INTRO_PRACTICE");
    /** 연습 시작 패키지 approvedSpeech 허용 목록(core StartActivityService.PRACTICE_APPROVED_SPEECH와 같은 code). */
    static final List<String> PRACTICE_SPEECH = List.of("HELP_OFFER", "VARIANT_OFFER", "RETRY_PROMPT", "INPUT_CONFIRM", "SCENE_CLOSE",
            "WAIT_LEAD", "ASK_DEFAULT", "D_REST_ACK_START", "D_REST_ACK");

    private static final JsonCodec JSON = JsonCodec.shared();
    private static final Pattern SEED_SPEECH_ROW = Pattern.compile(
            "\\('([0-9a-f-]{36})'::uuid,\\s*'([A-Z_]+)',\\s*(\\d+),\\s*'((?:[^']|'')*)'");
    private static final Pattern SEED_AUDIO_ROW = Pattern.compile(
            "\\('([0-9a-f-]{36})'::uuid,\\s*'([A-Z_]+)',\\s*(\\d+),\\s*'((?:[^']|'')*)',\\s*'([^']+\\.wav)'");

    // ------------------------------------------------------------------ 상한 · 시간 · 배선

    /** 상한. 넘으면 진행 중 대화를 끝내고(사유를 상태로) 남은 대화는 SKIPPED로 적는다. */
    record Caps(Duration dialogue, double gptLiveSeconds, int dialogues, int ttsCalls, int textCalls) {

        static Caps fromEnv(Function<String, String> env) {
            return new Caps(Duration.ofSeconds(positive(env, DIALOGUE_SECONDS_ENV, DEFAULT_DIALOGUE_SECONDS)),
                    positive(env, TOTAL_SECONDS_ENV, DEFAULT_TOTAL_SECONDS), positive(env, MAX_DIALOGUES_ENV, DEFAULT_MAX_DIALOGUES),
                    positive(env, TTS_CALLS_ENV, DEFAULT_TTS_CALLS), positive(env, TEXT_CALLS_ENV, DEFAULT_TEXT_CALLS));
        }

        /** env 정수(없으면 기본값). 숫자가 아니거나 0 이하면 부르기 전에 거절한다. */
        static int positive(Function<String, String> env, String name, int fallback) {
            String value = env.apply(name);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                int parsed = Integer.parseInt(value.strip());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException wrong) {
                // 아래에서 거절한다
            }
            throw new IllegalArgumentException("env " + name + " 값이 양의 정수가 아니다");
        }

        Map<String, Object> report() {
            return PracticeVoiceReport.ordered("dialogueSeconds", dialogue.toSeconds(), "gptLiveSecondsTotal", gptLiveSeconds,
                    "dialogues", dialogues, "ttsCalls", ttsCalls, "judgmentAndChildLineCalls", textCalls, "env",
                    List.of(DIALOGUE_SECONDS_ENV, TOTAL_SECONDS_ENV, MAX_DIALOGUES_ENV, TTS_CALLS_ENV, TEXT_CALLS_ENV, PLANS_ENV, GOALS_ENV));
        }
    }

    /**
     * 하니스 시간 값(운영 값이 아니다 — 운영 시간 값은 application.yml에서 읽어 실행부 · 흐름에 그대로 준다).
     *
     * @param quietGap 기기 출력이 이만큼 조용해야 아이가 말한다
     * @param trailingSilence 아이 한 줄 뒤 다음 줄까지 적어도 두는 무음
     * @param poll 아이 쪽 판단 주기
     * @param endWait 하니스가 끝내기를 요청한 뒤 세션이 닫히기를 기다리는 시간
     * @param transcriptWait 아이가 말한 뒤 흐름이 그 말을 받기를 기다리는 시간
     * @param startWait 참여 확인(WAITING_CHILD)까지 기다리는 시간
     */
    record Timing(Duration quietGap, Duration trailingSilence, Duration poll, Duration endWait, Duration transcriptWait, Duration startWait) {

        static Timing live() {
            return new Timing(Duration.ofMillis(1200), Duration.ofMillis(1500), Duration.ofMillis(50), Duration.ofSeconds(60),
                    Duration.ofSeconds(12), Duration.ofSeconds(40));
        }

        Map<String, Object> report() {
            return PracticeVoiceReport.ordered("quietGap", quietGap.toString(), "trailingSilence", trailingSilence.toString(), "endWait",
                    endWait.toString(), "transcriptWait", transcriptWait.toString(), "startWait", startWait.toString(), "source",
                    "하니스 값(운영 값 아님). 운영 시간 값은 application.yml을 실행부 · 흐름에 그대로 준다");
        }
    }

    /** 실호출과 점검이 다른 부분(보낼 곳). 하니스가 운영 설정을 묶은 뒤 만든다. */
    interface Backends extends AutoCloseable {

        /** 증거 종류(R-M11). */
        String evidence();

        String evidenceLabel();

        /** GPT-Live 주소 규칙의 프로필(실호출 demo). */
        String providerProfile();

        /** 대화마다 새 공급자 포트(실호출: 운영 ProviderClient). */
        ProviderPort openProvider(RealtimeProperties properties);

        void closeProvider(ProviderPort port);

        /** 운영 판정 부품(실호출: DecisionsJudgeClient → RealtimeLunaClient). 사건 로그는 그 대화의 기록기다. */
        JudgeClient judge(PracticeVoiceHarness harness, EventLogger events);

        /** 제브 포트(실호출: env가 모두 있으면 JevGuardrailClient, 아니면 대역). */
        GuardrailClient guardrail();

        Map<String, Object> guardrailReport();

        PracticeVoiceChild.Speech speech();

        PracticeVoiceChild.LineWriter lines();

        /** 시작 전에 막는 사유(주소 규칙 · 제브 기동 점검, 값 없이 이름 · 사유만). 비면 통과. */
        List<String> problems();

        @Override
        void close();
    }

    @FunctionalInterface
    interface BackendsFactory {
        Backends create(PracticeVoiceHarness harness) throws IOException;
    }

    /**
     * 배선 묶음.
     *
     * @param propertyResources 운영 설정 트리로 묶을 실행 자원 YAML(뒤 파일이 앞 파일을 덮는다)
     * @param jevMode 제브 모드(실호출은 env JEV_API_KEY · JEV_BASE_URL · JEV_MODEL이 모두 있으면 REAL, 아니면 STUB)
     */
    record Wiring(Function<String, String> env, Path repoRoot, Caps caps, Timing timing, List<Plan> plans, Set<String> goalFilter,
            List<String> propertyResources, GuardrailMode jevMode, BackendsFactory backends) {

        static Wiring live(Function<String, String> env) {
            boolean jevReady = present(env, JEV_API_KEY_ENV) && present(env, JEV_BASE_URL_ENV) && present(env, JEV_MODEL_ENV);
            return new Wiring(env, PracticeVoiceDialogueLiveTest.repoRoot(), Caps.fromEnv(env), Timing.live(),
                    Plan.parse(env.apply(PLANS_ENV)), PracticeVoiceHarness.goalFilter(env.apply(GOALS_ENV)), List.of("application.yml", "application-demo.yml"),
                    jevReady ? GuardrailMode.REAL : GuardrailMode.STUB, LiveBackends::new);
        }

        @Override
        public String toString() {
            return "Wiring[jevMode=" + jevMode + "]";
        }
    }

    static boolean present(Function<String, String> env, String name) {
        String value = env.apply(name);
        return value != null && !value.isBlank();
    }

    static Set<String> goalFilter(String value) {
        Set<String> keys = new LinkedHashSet<>();
        if (value != null) {
            for (String part : value.split(",")) {
                if (!part.isBlank()) {
                    keys.add(part.strip());
                }
            }
        }
        return Set.copyOf(keys);
    }

    /** 하니스 전체 사용량(대화 사이에 이어진다). */
    static final class Budget {

        final Caps caps;
        private int judgments;
        private int childLines;
        private int tts;
        private int ttsRefused;
        private int textRefused;
        private double gptLiveSeconds;
        private int gptLiveSessions;

        Budget(Caps caps) {
            this.caps = caps;
        }

        synchronized boolean takeJudgment() {
            if (judgments + childLines >= caps.textCalls()) {
                textRefused++;
                return false;
            }
            judgments++;
            return true;
        }

        synchronized boolean takeChildLines() {
            if (judgments + childLines >= caps.textCalls()) {
                textRefused++;
                return false;
            }
            childLines++;
            return true;
        }

        synchronized boolean takeTts() {
            if (tts >= caps.ttsCalls()) {
                ttsRefused++;
                return false;
            }
            tts++;
            return true;
        }

        synchronized void finishSession(double seconds, boolean started) {
            gptLiveSeconds += Math.max(0, seconds);
            if (started) {
                gptLiveSessions++;
            }
        }

        synchronized double gptLiveSeconds() {
            return gptLiveSeconds;
        }

        synchronized int gptLiveSessions() {
            return gptLiveSessions;
        }

        synchronized boolean ttsExhausted() {
            return tts >= caps.ttsCalls();
        }

        synchronized boolean textExhausted() {
            return judgments + childLines >= caps.textCalls();
        }

        synchronized Map<String, Object> report() {
            return PracticeVoiceReport.ordered("judgmentCalls", judgments, "childLineCalls", childLines, "openAiTextCalls",
                    judgments + childLines, "textCallsRefusedByCap", textRefused, "ttsCalls", tts, "ttsCallsRefusedByCap", ttsRefused,
                    "gptLiveSessions", gptLiveSessions, "gptLiveSecondsCounted", PracticeVoiceReport.round1(gptLiveSeconds));
        }
    }

    /** 미리 만든 아이 말 소리 하나(TTS 결과 · 캐시 여부 · 상한 거절). */
    record Prepared(PracticeVoiceDevice.Clip clip, String outcome, long latencyMs, boolean cached, boolean capRefused) {

        Prepared asCached() {
            return new Prepared(clip, outcome, latencyMs, true, false);
        }

        Map<String, Object> report(String text) {
            return PracticeVoiceReport.ordered("text", text, "outcome", outcome, "latencyMs", latencyMs, "cached", cached, "audioMs",
                    clip == null ? null : clip.audioMs(), "capRefused", capRefused);
        }
    }

    // ------------------------------------------------------------------ 승인 문구

    /** 음성 있는 승인 문구 하나(시작 패키지 approvedContent, 길이는 승인 음성 기록의 길이). */
    record ApprovedAudio(String code, UUID versionId, String text, String file, long durationMs) {
    }

    /** 음성 없는 승인 발화 하나(시작 패키지 approvedSpeech). */
    record ApprovedSpeech(String code, UUID versionId, String text, String source) {
    }

    /** 승인 문구 목록(저장소 시드 · 승인 음성 기록에서 읽는다 — core가 시작 패키지에 싣는 것과 같은 판). */
    record ApprovedCatalog(Map<String, ApprovedAudio> content, Map<String, ApprovedSpeech> speech, List<String> problems) {

        Map<String, String> speechTexts() {
            Map<String, String> texts = new LinkedHashMap<>();
            speech.forEach((code, item) -> texts.put(code, item.text()));
            return texts;
        }

        Map<String, Long> audioMs() {
            Map<String, Long> lengths = new LinkedHashMap<>();
            content.forEach((code, item) -> lengths.put(code, item.durationMs()));
            return lengths;
        }

        Map<String, String> contentTexts() {
            Map<String, String> texts = new LinkedHashMap<>();
            content.forEach((code, item) -> {
                if (item.text() != null) {
                    texts.put(code, item.text());
                }
            });
            return texts;
        }

        Map<String, UUID> contentVersions() {
            Map<String, UUID> versions = new LinkedHashMap<>();
            content.forEach((code, item) -> versions.put(code, item.versionId()));
            return versions;
        }

        Map<String, LiveContentRegistry.SpeechText> registrySpeech() {
            Map<String, LiveContentRegistry.SpeechText> texts = new LinkedHashMap<>();
            speech.forEach((code, item) -> texts.put(code, new LiveContentRegistry.SpeechText(item.versionId(), item.text())));
            return texts;
        }

        @SuppressWarnings("unchecked")
        static ApprovedCatalog load(Path repoRoot) throws IOException {
            List<String> problems = new ArrayList<>();
            Map<UUID, String> spokenTexts = new HashMap<>();
            Path spoken = repoRoot.resolve(SPOKEN_SEED);
            if (Files.isRegularFile(spoken)) {
                Matcher matcher = SEED_AUDIO_ROW.matcher(Files.readString(spoken, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    spokenTexts.put(UUID.fromString(matcher.group(1)), matcher.group(4).replace("''", "'"));
                }
            } else {
                problems.add("SPOKEN_SEED_MISSING");
            }
            Map<String, Object> approval = JSON.read(Files.readString(repoRoot.resolve(APPROVAL_FILE), StandardCharsets.UTF_8), Map.class);
            Map<String, ApprovedAudio> found = new HashMap<>();
            if (approval.get("assets") instanceof List<?> assets) {
                for (Object asset : assets) {
                    if (!(asset instanceof Map<?, ?> row) || !(row.get("contentCode") instanceof String code) || !PRACTICE_CONTENT.contains(code)) {
                        continue;
                    }
                    UUID version = UUID.fromString(String.valueOf(row.get("contentVersionId")));
                    long durationMs = row.get("seconds") instanceof Number seconds ? Math.round(seconds.doubleValue() * 1000) : 2_000L;
                    String text = spokenTexts.get(version);
                    if (text == null) {
                        problems.add("APPROVED_TEXT_MISSING:" + code);
                    }
                    found.put(code, new ApprovedAudio(code, version, text, String.valueOf(row.get("file")), durationMs));
                }
            }
            Map<String, ApprovedAudio> content = new LinkedHashMap<>();
            for (String code : PRACTICE_CONTENT) {
                if (found.containsKey(code)) {
                    content.put(code, found.get(code));
                } else {
                    problems.add("APPROVED_AUDIO_MISSING:" + code);
                }
            }
            if (!content.containsKey("CLOSING") || !content.containsKey("WAITING_FILLER") || !content.containsKey("INTRO_PRACTICE")) {
                throw new IllegalStateException("승인 음성 기록에 CLOSING · WAITING_FILLER · INTRO_PRACTICE가 없다(" + APPROVAL_FILE + ")");
            }
            Map<String, Integer> versions = new HashMap<>();
            Map<String, ApprovedSpeech> parsed = new HashMap<>();
            for (String file : SPEECH_SEEDS) {
                Path path = repoRoot.resolve(file);
                if (!Files.isRegularFile(path)) {
                    problems.add("SPEECH_SEED_MISSING:" + file);
                    continue;
                }
                Matcher matcher = SEED_SPEECH_ROW.matcher(Files.readString(path, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    String code = matcher.group(2);
                    int version = Integer.parseInt(matcher.group(3));
                    if (PRACTICE_SPEECH.contains(code) && version > versions.getOrDefault(code, 0)) {
                        versions.put(code, version);
                        parsed.put(code, new ApprovedSpeech(code, UUID.fromString(matcher.group(1)), matcher.group(4).replace("''", "'"),
                                file + " v" + version));
                    }
                }
            }
            Map<String, ApprovedSpeech> speech = new LinkedHashMap<>();
            for (String code : PRACTICE_SPEECH) {
                ApprovedSpeech item = parsed.get(code);
                if (item == null && DirectAskLines.FIXED.containsKey(code)) {
                    // 시드를 읽지 못한 고정 문장은 같은 문장의 상수로 대신한다(흐름도 패키지에 없으면 같은 상수를 쓴다)
                    problems.add("SPEECH_SEED_ROW_MISSING:" + code);
                    item = new ApprovedSpeech(code, UUID.nameUUIDFromBytes(("harness:" + code).getBytes(StandardCharsets.UTF_8)),
                            DirectAskLines.FIXED.get(code), "DirectAskLines.FIXED");
                }
                if (item != null) {
                    speech.put(code, item);
                }
            }
            return new ApprovedCatalog(content, speech, List.copyOf(problems));
        }

        Map<String, Object> report() {
            Map<String, Object> audio = new LinkedHashMap<>();
            content.forEach((code, item) -> audio.put(code, PracticeVoiceReport.ordered("text", item.text(), "durationMs", item.durationMs())));
            Map<String, Object> texts = new LinkedHashMap<>();
            speech.forEach((code, item) -> texts.put(code, PracticeVoiceReport.ordered("text", item.text(), "source", item.source())));
            return PracticeVoiceReport.ordered("approvedContent", audio, "approvedSpeech", texts, "source",
                    APPROVAL_FILE + " · " + SPOKEN_SEED + " · " + String.join(" · ", SPEECH_SEEDS)
                            + "(core 시작 패키지 approvedContent · approvedSpeech와 같은 판)", "problems", problems);
        }
    }

    // ------------------------------------------------------------------ 입력

    /** 진행할 목표 하나(시작 패키지 practice 조각, 첫 목표만). */
    record GoalInput(String key, String file, String source, String json, String goalName, String criteria, PracticeScript script,
            Map<String, Object> row) {
    }

    /** 대화 하나의 계획(목표 · 아이 계획 · 순번). */
    record DialoguePlan(int index, GoalInput goal, Plan plan) {
    }

    /** 실행 결과(콘솔에는 개수와 파일 위치만). */
    record Outcome(int ranDialogues, int completed, int skipped, int gptLiveSessions, Map<String, Object> usage, String evidence, Path file,
            Path markdown) {

        String consoleLine() {
            return "PracticeVoiceDialogueLiveTest: 대화 " + ranDialogues + "건(끝까지 " + completed + " · 건너뜀 " + skipped + "), GPT-Live 세션 "
                    + gptLiveSessions + "개, 사용량 " + usage + ", 증거=" + evidence + ", 파일=" + file + ", 대화 기록=" + markdown;
        }
    }

    // ------------------------------------------------------------------ 하니스 상태

    final Path out;
    final Wiring wiring;
    final MujoongClock clock = new SystemMujoongClock();
    final Map<String, Object> summary = new LinkedHashMap<>();
    RealtimeProperties properties;
    LunaProperties luna;
    GuardrailProperties jev;
    LiveContentRegistry registry;
    JudgmentPrompt prompt;
    LunaSchemas lunaSchemas;
    SeedDictionary dictionary;
    ApprovedCatalog catalog;
    Budget budget;
    Backends backends;
    private final Map<String, Prepared> clips = new LinkedHashMap<>();
    private final Map<String, Integer> ttsFailures = new LinkedHashMap<>();
    private final Map<String, GoalLines> goalLines = new HashMap<>();
    private volatile String abortReason;

    PracticeVoiceHarness(Path out, Wiring wiring) {
        this.out = out;
        this.wiring = wiring;
    }

    Timing timing() {
        return wiring.timing();
    }

    Caps caps() {
        return wiring.caps();
    }

    /** 대화마다 제브 장치(운영 GuardrailConfiguration · RealtimeRuntimeConfig와 같은 조립, 포트만 관찰자로 감싼다). */
    GuardrailSupport guardrailFor(GuardrailClient observed) {
        return new GuardrailSupport(observed.mode(), observed, null, properties.timing().guardrail().jevBudget())
                .withSendChildName(jev.sendChildName())
                .withPolicyVersion(registry.resources().guardrail().version())
                .withApprovedVerbatim(ApprovedVerbatimPort.of(registry));
    }

    GuardrailClient guardrailClient() {
        return backends.guardrail();
    }

    /** 운영 GuardrailConfiguration.question과 같은 일: 정책 판 → 제브 질문(세션에 고정된 정책 리소스, 판이 다르면 빈 값). */
    Optional<JevQuestion> jevQuestion(String policyVersion) {
        var policy = registry.resources().guardrail();
        return policy.version().equals(policyVersion) ? Optional.of(new JevQuestion(policy.instructions(), policy.criteria())) : Optional.empty();
    }

    /** 아이 말 한 줄의 소리(TTS, 같은 글은 한 번만 만든다). 상한을 넘으면 만들지 않는다. */
    synchronized Prepared prepare(String text) {
        Prepared cached = clips.get(text);
        if (cached != null) {
            return cached.asCached();
        }
        if (!budget.takeTts()) {
            return new Prepared(null, "HARNESS_TTS_CAP", 0, false, true);
        }
        PracticeVoiceChild.Synth synth = backends.speech().synthesize(text);
        if (!synth.ok()) {
            ttsFailures.merge(synth.outcome(), 1, Integer::sum);
            if ("AUTH_FAILED".equals(synth.outcome()) || "NO_KEY".equals(synth.outcome())) {
                abortReason = "TTS_" + synth.outcome();
            }
            return new Prepared(null, synth.outcome(), synth.latencyMs(), false, false);
        }
        Prepared made = new Prepared(PracticeVoiceDevice.Clip.of(synth.pcm()), "OK", synth.latencyMs(), false, false);
        clips.put(text, made);
        return made;
    }

    // ------------------------------------------------------------------ 실행

    Outcome run() throws IOException {
        Files.createDirectories(out);
        PracticeVoiceDialogueLiveTest.movePreviousResult(out);
        summary.put("harness", "PracticeVoiceDialogueLiveTest(realtime, 연습 대화 C안 실음성)");
        summary.put("outcome", "RUNNING");
        summary.put("startedAt", clock.now().toString());
        List<Map<String, Object>> goalRows = new ArrayList<>();
        List<PracticeVoiceReport.DialogueRun> runs = new ArrayList<>();
        List<Map<String, Object>> skippedRows = new ArrayList<>();
        budget = new Budget(wiring.caps());
        int ran = 0;
        int completed = 0;
        try {
            bind();
            registry = new LiveContentRegistry(LiveContentResources.loadFor(properties.practiceFlow().flexibleDialogue()));
            prompt = JudgmentPrompt.load(registry.resources().judgment());
            lunaSchemas = LunaSchemas.loadDefault(JSON);
            dictionary = SeedDictionary.loadDefault();
            catalog = ApprovedCatalog.load(wiring.repoRoot());
            backends = wiring.backends().create(this);
            summary.put("evidence", backends.evidence());
            summary.put("evidenceLabel", backends.evidenceLabel());
            summary.put("notes", notes());
            summary.put("setup", setup());
            summary.put("caps", wiring.caps().report());
            summary.put("timing", wiring.timing().report());
            summary.put("plans", plansReport());
            summary.put("syntheticChild", PracticeVoiceReport.ordered("displayName", PracticeVoiceChild.CHILD_NAME, "ageYears",
                    PracticeVoiceChild.CHILD_AGE, "interests", PracticeVoiceChild.CHILD_INTERESTS, "kind", "합성 프로필(실제 아동 아님)", "voice",
                    "TTS " + backends.speech().model() + " · " + backends.speech().voice()));
            List<String> problems = backends.problems();
            if (!problems.isEmpty()) {
                throw new IllegalStateException("시작 전 점검 실패(값 미출력): " + String.join(", ", problems));
            }
            Inputs inputs = loadInputs(out, wiring.repoRoot(), wiring.goalFilter());
            summary.put("source", inputs.source());
            goalRows.addAll(inputs.rows());
            summary.put("goals", goalRows);
            List<DialoguePlan> plans = order(inputs.goals(), wiring.plans());
            summary.put("dialoguePlan", PracticeVoiceReport.ordered("combinations", plans.size(), "rule",
                    "k번째 대화(0부터) = 계획[k mod 계획 수] × 목표[(k mod 계획 수 + k div 계획 수) mod 목표 수] — 앞의 대화들이 계획 7가지와 목표를 고르게 덮는다"));
            for (DialoguePlan plan : plans) {
                String skip = skipReason(ran);
                if (skip != null) {
                    skippedRows.add(PracticeVoiceReport.ordered("index", plan.index(), "goal", plan.goal().key(), "plan", plan.plan().name(),
                            "skipReason", skip));
                    continue;
                }
                GoalLines lines = linesFor(plan.goal());
                ran++;
                PracticeVoiceReport.DialogueRun run = new PracticeVoiceSession(this, plan, lines).run();
                runs.add(run);
                if ("COMPLETED".equals(run.metrics().status())) {
                    completed++;
                }
            }
            summary.put("outcome", inputs.goals().isEmpty() ? "NO_INPUT" : "COMPLETED");
        } catch (RuntimeException | IOException failure) {
            // 중간에 끊겨도 그때까지 모은 기록은 남긴다(원인은 예외 종류와 사유 코드만)
            summary.put("outcome", "ABORTED");
            summary.put("aborted", failure.getClass().getSimpleName());
            summary.put("abortReason", failure instanceof IllegalStateException && failure.getMessage() != null
                    && failure.getMessage().startsWith("시작 전 점검 실패") ? failure.getMessage() : failure.getClass().getSimpleName());
            throw failure;
        } finally {
            summary.put("finishedAt", clock.now().toString());
            summary.put("dialogues", runs.stream().map(PracticeVoiceReport.DialogueRun::row).toList());
            summary.put("skippedDialogues", skippedRows);
            summary.put("summary", PracticeVoiceReport.summary(runs));
            summary.put("totals", totals(ran, completed, skippedRows.size()));
            PracticeVoiceReport.writePretty(out.resolve(PracticeVoiceDialogueLiveTest.OUTPUT_FILE), summary);
            writeMarkdown(runs, skippedRows);
            if (backends != null) {
                backends.close();
            }
        }
        return new Outcome(ran, completed, skippedRows.size(), budget.gptLiveSessions(), budget.report(),
                String.valueOf(summary.get("evidence")), out.resolve(PracticeVoiceDialogueLiveTest.OUTPUT_FILE),
                out.resolve(PracticeVoiceDialogueLiveTest.MARKDOWN_FILE));
    }

    /** 남은 상한이 없으면 사유(대화 수 · GPT-Live 초 · TTS · 판정과 아이 말 호출 · TTS 인증 실패). */
    private String skipReason(int ran) {
        if (abortReason != null) {
            return abortReason;
        }
        if (ran >= wiring.caps().dialogues()) {
            return "DIALOGUE_CAP";
        }
        if (budget.gptLiveSeconds() >= wiring.caps().gptLiveSeconds()) {
            return "GPT_LIVE_SECONDS_CAP";
        }
        if (budget.ttsExhausted()) {
            return "TTS_CALL_CAP";
        }
        if (budget.textExhausted()) {
            return "TEXT_CALL_CAP";
        }
        return null;
    }

    /** 운영 설정 트리(application.yml + application-demo.yml)와 Luna · 제브 설정을 묶는다. */
    private void bind() throws IOException {
        Function<String, String> env = wiring.env();
        Map<String, Object> placeholders = new LinkedHashMap<>();
        // 운영 앱은 env를 자리표시자로 읽는다. 하니스는 쓰는 이름만 넘긴다(그 밖의 env가 설정을 바꾸지 않게)
        placeholders.put("MUJOONG_SECURITY_ALLOWED_ORIGIN", "https://mujoong.invalid");
        placeholders.put("JEV_MODE", wiring.jevMode().name());
        for (String name : List.of(JUDGE_MODEL_ENV, JEV_BASE_URL_ENV, JEV_MODEL_ENV, JEV_PASS_THRESHOLD_ENV, JEV_SEND_CHILD_NAME_ENV)) {
            if (present(env, name)) {
                placeholders.put(name, env.apply(name).strip());
            }
        }
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("harness-env", placeholders));
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String resource : wiring.propertyResources()) {
            List<PropertySource<?>> loaded = loader.load(resource, new ClassPathResource(resource));
            for (int i = loaded.size() - 1; i >= 0; i--) {
                sources.addFirst(loaded.get(i));
            }
        }
        // 자리표시자 값이 YAML보다 뒤(우선순위 낮음)여야 YAML 키를 덮지 않는다
        sources.remove("harness-env");
        sources.addLast(new MapPropertySource("harness-env", placeholders));
        Binder binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources),
                ApplicationConversionService.getSharedInstance());
        properties = binder.bind("mujoong", Bindable.of(RealtimeProperties.class))
                .orElseThrow(() -> new IllegalStateException("mujoong 설정을 묶지 못했다"));
        luna = binder.bind("mujoong.luna", Bindable.of(LunaProperties.class))
                .orElseThrow(() -> new IllegalStateException("mujoong.luna 설정을 묶지 못했다"));
        jev = binder.bind("mujoong.guardrail.jev", Bindable.of(GuardrailProperties.class))
                .orElseThrow(() -> new IllegalStateException("mujoong.guardrail.jev 설정을 묶지 못했다"));
    }

    /** demo 프로필 파일의 GPT-Live 주소(코드에 주소를 적지 않는다, R-F3). 묶은 설정에 이미 있다. */
    URI providerUrl() {
        URI url = properties.provider().url();
        if (url == null || url.toString().isBlank()) {
            throw new IllegalStateException("application-demo.yml에 mujoong.provider.url이 없다");
        }
        return url;
    }

    /** 목표 하나의 아이 말(Luna 한 번, 목표마다 한 번만 부르고 계획들이 함께 쓴다). */
    private GoalLines linesFor(GoalInput goal) {
        GoalLines cached = goalLines.get(goal.key());
        if (cached != null) {
            return cached;
        }
        PracticeScript script = goal.script();
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("requestedModel", backends.lines().model());
        call.put("modelSource", backends.lines().modelSource());
        GoalLines lines;
        if (!budget.takeChildLines()) {
            lines = PracticeVoiceChild.fallback(script, "TEXT_CALL_CAP", call);
        } else {
            String input = PracticeVoiceChild.input(goal.goalName(), goal.criteria(), script, catalog.speechTexts());
            Map<String, Object> schema = PracticeVoiceChild.schema(script.variantScene().isPresent());
            PracticeVoiceChild.Reply reply = backends.lines().write(PracticeVoiceChild.CHILD_INSTRUCTIONS, input, schema);
            call.put("attempts", new ArrayList<>(List.of(replyReport(reply, "json_schema"))));
            if (!reply.ok() && reply.outcome().startsWith("REQUEST_REJECTED") && budget.takeChildLines()) {
                // 구조화 출력(json_schema)을 받지 않는 모델일 수 있어 JSON 객체 출력으로 한 번만 다시 묻는다
                reply = backends.lines().write(PracticeVoiceChild.CHILD_INSTRUCTIONS
                        + "\n출력: base(변형 장면이 있으면 variant도) 아래에 위 칸 이름을 키로 하는 JSON 객체 하나만 쓴다.", input, null);
                @SuppressWarnings("unchecked")
                List<Object> attempts = (List<Object>) call.get("attempts");
                attempts.add(replyReport(reply, "json_object"));
            }
            lines = reply.ok() ? PracticeVoiceChild.parse(reply.text(), script, call)
                    : PracticeVoiceChild.fallback(script, "LUNA_" + reply.outcome(), call);
        }
        goalLines.put(goal.key(), lines);
        goal.row().put("childLines", lines.report());
        return lines;
    }

    private static Map<String, Object> replyReport(PracticeVoiceChild.Reply reply, String format) {
        return PracticeVoiceReport.ordered("format", format, "outcome", reply.outcome(), "responseModel", reply.model(), "latencyMs",
                reply.latencyMs(), "usage", reply.usage(), "errorCode", reply.errorCode());
    }

    // ------------------------------------------------------------------ 입력 읽기

    record Inputs(List<GoalInput> goals, List<Map<String, Object>> rows, Map<String, Object> source) {
    }

    /**
     * core 하니스가 남긴 시작 패키지({@value #PACKAGE_DIR}/*.json, 요약 {@value #CORE_SUMMARY_FILE}의 목표 순서 먼저)를 읽는다. 없으면 시험 고정 대본
     * 하나로 진행하고 그 사실을 적는다. 패키지마다 첫 목표만 남긴다(다음 목표 대본은 core 내부 호출이라 하니스가 받지 않는다).
     */
    @SuppressWarnings("unchecked")
    static Inputs loadInputs(Path out, Path repoRoot, Set<String> goalFilter) throws IOException {
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
            source.put("coreEvidence", core.get("evidence"));
            if (core.get("totals") instanceof Map<?, ?> totals && totals.get("lunaCalls") instanceof Number calls) {
                source.put("coreLunaCalls", calls.intValue());
            }
            if (core.get("goals") instanceof List<?> rows) {
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> goal && goal.get("startPackageFile") instanceof String relative) {
                        Path file = out.resolve(relative).normalize();
                        if (file.startsWith(dir) && Files.isRegularFile(file) && !files.contains(file)) {
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
        List<GoalInput> goals = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!files.isEmpty()) {
            source.put("kind", "CORE_HARNESS_START_PACKAGES");
            source.put("directory", PACKAGE_DIR + "/");
            if (core == null || core.containsKey("outcome")) {
                source.put("warning", "core 요약이 이번 실호출 결과가 아니다(없거나 SKIPPED). 패키지가 지난 실호출 것일 수 있다");
            }
            for (Path file : files) {
                String name = file.getFileName().toString();
                String key = name.substring(0, name.length() - ".json".length());
                if (!goalFilter.isEmpty() && !goalFilter.contains(key)) {
                    continue;
                }
                read(key, PACKAGE_DIR + "/" + name, "CORE_HARNESS", Files.readString(file, StandardCharsets.UTF_8), goals, rows);
            }
        } else {
            source.put("kind", "TEST_FIXTURE_V2");
            source.put("warning", "core 하니스 결과(" + PACKAGE_DIR + "/)가 없어 시험 고정 대본(PracticeScriptFixtures C안 v2 장면) 하나로 진행했다."
                    + " 실호출로 만든 대본이 아니다");
            read("fixture-direct-ask", "PracticeScriptFixtures(시험 고정 자료)", "TEST_FIXTURE", fixturePackage(), goals, rows);
        }
        source.put("files", rows.stream().map(row -> row.get("startPackageFile")).toList());
        source.put("goalRule", "패키지마다 첫 목표만 진행한다(다음 목표 대본은 core 내부 호출이라 하니스가 받지 않는다)");
        return new Inputs(List.copyOf(goals), rows, source);
    }

    /** 패키지 하나를 읽어 진행할 수 있으면 목표를 더한다(못 읽으면 그 사실만 적는다). */
    @SuppressWarnings("unchecked")
    private static void read(String key, String file, String sourceKind, String json, List<GoalInput> goals, List<Map<String, Object>> rows) {
        Map<String, Object> row = new LinkedHashMap<>();
        rows.add(row);
        row.put("key", key);
        row.put("startPackageFile", file);
        row.put("source", sourceKind);
        Map<String, Object> raw;
        try {
            raw = JSON.read(json, Map.class);
        } catch (RuntimeException unreadable) {
            row.put("result", "BAD_JSON");
            return;
        }
        // 시작 요청 전체가 왔으면 practice 조각만 쓴다
        Map<String, Object> practice = raw.get("practice") instanceof Map<?, ?> nested ? (Map<String, Object>) nested : raw;
        List<Object> list = practice.get("goals") instanceof List<?> items ? (List<Object>) items : List.of();
        Map<String, Object> first = list.isEmpty() || !(list.getFirst() instanceof Map<?, ?>) ? Map.of() : (Map<String, Object>) list.getFirst();
        Map<String, Object> trimmed = new LinkedHashMap<>(practice);
        trimmed.put("goals", first.isEmpty() ? List.of() : List.of(first));
        String fragment = JSON.write(trimmed);
        row.put("goalsInPackage", list.size());
        row.put("goal", PracticeVoiceReport.ordered("displayName", first.get("displayName"), "judgmentCriteria", first.get("judgmentCriteria")));
        PracticeStartPackage pack;
        try {
            pack = PracticeStartPackage.parse(fragment);
        } catch (RuntimeException unreadable) {
            row.put("result", "BAD_PACKAGE");
            row.put("failure", unreadable.getClass().getSimpleName());
            return;
        }
        row.put("scriptVersion", pack.scriptVersion());
        if (!pack.usesNewFlow()) {
            row.put("result", "NOT_NEW_FLOW_SCRIPT");
            return;
        }
        PracticeScript script = pack.firstScenario();
        if (script == null) {
            row.put("result", "SCRIPT_REJECTED");
            row.put("rejection", pack.firstScenarioRejection() == null ? null : pack.firstScenarioRejection().name());
            return;
        }
        row.put("result", "RAN");
        row.put("scenes", PracticeVoiceReport.sceneReport(script));
        goals.add(new GoalInput(key, file, sourceKind, fragment, pack.firstGoal().goalName(), pack.firstGoal().judgmentCriteria(), script, row));
    }

    /** core 결과가 없을 때 쓰는 시험 고정 시작 패키지(C안 v2 장면 · 변형, 목표 문구는 장면에 맞춘 시험 값). */
    static String fixturePackage() {
        UUID goal = UUID.nameUUIDFromBytes("harness-fixture-goal".getBytes(StandardCharsets.UTF_8));
        UUID scenario = UUID.nameUUIDFromBytes("harness-fixture-scenario".getBytes(StandardCharsets.UTF_8));
        return "{\"goals\":[{\"goalSelectionId\":\"" + goal + "\",\"order\":1,\"displayName\":\"친구 마음 헤아려 말하기\","
                + "\"behaviorKey\":\"EMPATHY\",\"behaviorVersion\":1,\"judgmentCriteria\":\"친구가 힘들다고 할 때 친구 마음을 먼저 묻거나 다른 방법을 말한다\","
                + "\"firstScenario\":" + PracticeScriptFixtures.directAskScriptWithVariant(scenario) + "}]}";
    }

    /** 목표 × 계획 대화 순서(앞의 대화들이 계획을 모두 덮고 목표에 고르게 퍼지게 한다). */
    static List<DialoguePlan> order(List<GoalInput> goals, List<Plan> plans) {
        List<DialoguePlan> ordered = new ArrayList<>();
        if (goals.isEmpty() || plans.isEmpty()) {
            return ordered;
        }
        Set<String> seen = new LinkedHashSet<>();
        int goalCount = goals.size();
        int planCount = plans.size();
        for (int k = 0; k < goalCount * planCount; k++) {
            Plan plan = plans.get(k % planCount);
            // 바퀴(k div 계획 수)마다 목표를 하나씩 밀어 앞 바퀴와 다른 짝을 만든다
            GoalInput goal = goals.get((k % planCount + k / planCount) % goalCount);
            if (seen.add(goal.key() + "|" + plan)) {
                ordered.add(new DialoguePlan(ordered.size() + 1, goal, plan));
            }
        }
        for (GoalInput goal : goals) {
            for (Plan plan : plans) {
                if (seen.add(goal.key() + "|" + plan)) {
                    ordered.add(new DialoguePlan(ordered.size() + 1, goal, plan));
                }
            }
        }
        return ordered;
    }

    // ------------------------------------------------------------------ 기록

    private Map<String, Object> setup() {
        Map<String, Object> setup = new LinkedHashMap<>();
        setup.put("engine", "운영 SessionExecutor(게이트 · 출력 보류 · 승인 원문 대조 · 입력 전사 묶기 · 무응답 시계 협력) + PracticeFlowRouter → "
                + "PracticeConversation(C안 '무중이 직접 묻기', flexible-dialogue=" + properties.practiceFlow().flexibleDialogue() + ") + 운영 "
                + "ProviderClient · ProviderConnector + 운영 판정 조립(DecisionsJudgeClient → RealtimeLunaClient) + 운영 제브 장치(GuardrailSupport)");
        setup.put("replacedForTheHarness", List.of("기기 WSS 끝점 · 연결 송신기 → 시험용 가상 기기 연결(DeviceLink)",
                "세션 행 전이 → 메모리 전이 장치(InMemoryTransitions)", "기록기 DB 저장 → 메모리 저장소(같은 AsyncRecorder)",
                "흐름 기록(PracticeFlowRecorder · DB) → 메모리 기록 자리(MemoryRecorder)", "판정 기록 · 제브 검사 기록(DB) → 남기지 않음",
                "core 내부 호출(다음 목표 대본) → 쓰지 않음(첫 목표만 진행)"));
        setup.put("providerHostRule", backends.providerProfile() + "(운영 ProviderHosts 규칙)");
        setup.put("gptLiveModel", ProviderRequestFactory.MODEL);
        setup.put("judgment", PracticeVoiceReport.ordered("requestedModel", luna.model(), "modelSource",
                "application.yml mujoong.luna.model(env " + JUDGE_MODEL_ENV + "가 있으면 그 값)", "path", "Decisions API(운영 DecisionsJudgeClient)",
                "promptVersion", prompt.version(), "optionSetVersion", registry.resources().judgment().version(), "liveBudget",
                properties.timing().judgment().liveBudget().toString() + "(운영 값 그대로)", "minConfidence",
                properties.judgment().minConfidence(), "minMargin", properties.judgment().minMargin()));
        setup.put("guardrail", backends.guardrailReport());
        setup.put("childLines", PracticeVoiceReport.ordered("requestedModel", backends.lines().model(), "modelSource",
                backends.lines().modelSource(), "path", "Responses API(하니스 쪽 보내기, 구조화 출력)", "granularity",
                "목표마다 한 번 만들어 그 목표의 계획들이 함께 쓴다", "input", "목표 · 장면 설명 · 아이가 들을 장면 안 승인 문장(대본 칸)"));
        setup.put("tts", PracticeVoiceReport.ordered("model", backends.speech().model(), "voice", backends.speech().voice(), "format",
                "pcm(24kHz 16비트 모노)", "instructions", backends.speech().sendsInstructions() ? PracticeVoiceChild.TTS_INSTRUCTIONS : null,
                "env", List.of(TTS_MODEL_ENV, TTS_VOICE_ENV), "preSynthesis", "대화 전에 계획이 쓸 말을 모두 만든다(같은 글은 한 번)"));
        var settings = kr.mujoong.realtime.session.SessionExecutor.Settings.from(properties);
        PracticeFlowSettings flow = PracticeFlowSettings.from(properties);
        setup.put("productionTiming", PracticeVoiceReport.ordered("segmentQuiet", settings.segmentQuiet().toString(),
                "inputTranscriptQuiet", settings.inputTranscriptQuiet().toString(), "newSpeechMargin", settings.newSpeechMargin().toString(),
                "instructionWait(response-ready)", settings.instructionWait().toString(), "joinWait", settings.lifecycle().joinWait().toString(),
                "noResponseWait", flow.noResponseWait().toString(), "focusMax", flow.focusMax().toString(), "goalActiveLimit",
                flow.goalActiveLimit().toString(), "slotGuard", flow.slotGuard().toString(), "jevBudget",
                properties.timing().guardrail().jevBudget().toString(), "retryMax", flow.retryMax(), "reexplainMax", flow.reexplainMax(),
                "source", String.join(" + ", wiring.propertyResources())));
        setup.put("approved", catalog.report());
        return setup;
    }

    private Map<String, Object> plansReport() {
        Map<String, Object> plans = new LinkedHashMap<>();
        for (Plan plan : wiring.plans()) {
            plans.put(plan.name(), PracticeVoiceReport.ordered("label", plan.label, "description", plan.description, "variantOffer",
                    plan.acceptsVariant() ? "수락" : "거절"));
        }
        return plans;
    }

    private List<String> notes() {
        return List.of(
                "증거 종류: " + backends.evidenceLabel() + ". 실제 기기 · 실제 아이 결과가 아니다(R-M11). 아이 말은 합성 인물(하늘이)의 합성 음성이다.",
                "세션 엔진은 운영 코드 그대로다(SessionExecutor · PracticeConversation · ProviderClient · 판정 조립 · 게이트 · 제브 장치). 기기 연결 · "
                        + "세션 행 · 기록기 저장소 · 흐름 기록 자리만 시험 대역이다. 관찰은 시험 쪽 감싸기로만 한다.",
                "'들린 말'은 게이트를 지나 가상 기기가 재생을 시작한 GPT-Live 구간의 전사(운영 기록기의 승인 확정 행)와 기기가 재생한 승인 음성 문구다. "
                        + "버린 구간은 '안 들림'과 사유 코드(원문 불일치 APPROVED_TEXT_MISMATCH · 빈 구간 GATE_EMPTY · 새 말 없음 FIRST_OUTPUT_TIMEOUT 등)로 적는다.",
                "출처: 무중의 장면 안 말은 대본 칸 이름(spokenIntroduction · opportunityRelay+askPrompt · reaskPrompt · helpSpeech · friendReaction 등) "
                        + "또는 고정 code(HELP_OFFER · VARIANT_OFFER · RETRY_PROMPT · INPUT_CONFIRM · SCENE_CLOSE · WAIT_LEAD)이고, 장면 밖 말은 '생성형:<단계>'다.",
                "승인 원문 읽기 결과: 첫 시도 일치(FIRST_MATCH) · 나머지 다시 읽기로 다 들림(REREAD_MATCH) · 같은 글 재시도 일치(RETRY_MATCH) · "
                        + "'뜻 전하기' 생성형 복구(RECOVERED) · 앞부분만 들림(PARTIAL) · 실패(FAILED) · 미완(INCOMPLETE, 끝남 · 취소로 결과 없음).",
                "부분 재생: B1 조각 단위 승인 낭독이 중간에 끊겨 나간 부분까지만 재생 끝으로 닫힌 시도는 끝을 '앞부분만 들림(원문 n/m자)'로 적는다(n은 실행부가 "
                        + "흐름에 알린 들린 곳 위치). C안은 그 뒤 끊긴 문장부터 끝까지를 한 번 다시 읽고(시도 '나머지 다시 읽기'), 흐름 진단 code("
                        + "PracticeApprovedRemainderReread · PracticeApprovedRemainderCutAgain · PracticeApprovedRemainderWithoutWords)를 "
                        + "대화마다 센다.",
                "판정은 운영 Decisions 경로의 실제 Luna 결과다(지점 · 1 · 2순위 · 확률 · 지연). 판정 턴이 시한에 취소한 판정은 CANCELLED_AT_DEADLINE이다.",
                "무응답 시계 사건(걸림 · 거둠 · 울림)과 아이 말 시작(4회차 R1) · 안전 대기를 차례마다 적는다. '무응답 오작동'은 시계가 걸린 뒤 아이가 실제로 "
                        + "소리를 냈는데 무응답으로 울린 경우다.",
                "아이가 들은 GPT-Live 자동 응답: 연습 흐름의 말 요청이 열려 있지 않을 때 기기로 가서 재생된 GPT-Live 구간 수(0이어야 한다).",
                "제브: env JEV_API_KEY · JEV_BASE_URL · JEV_MODEL이 모두 있으면 운영 경로 그대로 REAL(JevGuardrailClient), 하나라도 없으면 STUB다. "
                        + "env는 있는지 여부만 적는다(R-F3).",
                "콘솔에는 개수와 파일 위치만 쓴다. 키 · 요청 머리 · 주소는 어떤 출력에도 쓰지 않는다(R-F3 · R-F14).");
    }

    private Map<String, Object> totals(int ran, int completed, int skipped) {
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("dialoguesRan", ran);
        totals.put("dialoguesCompleted", completed);
        totals.put("dialoguesSkipped", skipped);
        totals.putAll(budget == null ? Map.of() : budget.report());
        totals.put("ttsFailures", ttsFailures);
        totals.put("ttsDistinctLines", clips.size());
        if (abortReason != null) {
            totals.put("stoppedEarly", abortReason);
        }
        return totals;
    }

    private void writeMarkdown(List<PracticeVoiceReport.DialogueRun> runs, List<Map<String, Object>> skipped) throws IOException {
        String text;
        try {
            text = PracticeVoiceReport.markdown(summary, runs, skipped);
        } catch (RuntimeException failure) {
            text = "# 연습 음성 대화 기록" + System.lineSeparator() + System.lineSeparator() + "대화 기록을 만들지 못했다: "
                    + failure.getClass().getSimpleName() + "(" + PracticeVoiceDialogueLiveTest.OUTPUT_FILE + "를 본다)" + System.lineSeparator();
        }
        Files.writeString(out.resolve(PracticeVoiceDialogueLiveTest.MARKDOWN_FILE), text, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 실호출 보낼 곳

    /**
     * 실호출: GPT-Live는 demo 프로필 주소(운영 호스트 규칙), Luna 판정 · 아이 말 · TTS는 application.yml의 Luna 주소(STRICT), 제브는 env가 모두 있으면
     * 운영 JevGuardrailClient(주소 규칙 · 기동 점검 그대로)다. 키는 env에서만 읽고 어디에도 남기지 않는다.
     */
    static final class LiveBackends implements Backends {

        private final Function<String, String> env;
        private final URI providerUrl;
        private final LunaTransport transport;
        private final GuardrailClient guardrail;
        private final Map<String, Object> guardrailReport = new LinkedHashMap<>();
        private final List<String> problems = new ArrayList<>();
        private final PracticeVoiceChild.Speech speech;
        private final PracticeVoiceChild.LineWriter lines;
        private final String evidence;
        private final String evidenceLabel;

        LiveBackends(PracticeVoiceHarness harness) {
            this.env = harness.wiring.env();
            this.providerUrl = harness.providerUrl();
            ProviderHosts.check(providerUrl, "demo").ifPresent(problem -> problems.add("mujoong.provider.url(" + problem + ")"));
            LunaHosts.Mode strict = LunaHosts.Mode.STRICT;
            LunaHosts.check(harness.luna.baseUrl(), strict).ifPresent(problem -> problems.add("mujoong.luna.base-url(" + problem + ")"));
            Function<String, String> source = env;
            this.transport = new LunaTransport(harness.luna.baseUrl(), strict, () -> source.apply(LunaTransport.API_KEY_ENV),
                    harness.luna.maxResponseBytes());
            GuardrailMode mode = harness.wiring.jevMode();
            guardrailReport.put("mode", mode.name());
            guardrailReport.put("envPresent", PracticeVoiceReport.ordered(JEV_API_KEY_ENV, present(env, JEV_API_KEY_ENV), JEV_BASE_URL_ENV,
                    present(env, JEV_BASE_URL_ENV), JEV_MODEL_ENV, present(env, JEV_MODEL_ENV)));
            guardrailReport.put("rule", "세 env가 모두 있으면 REAL(운영 JevGuardrailClient), 하나라도 없으면 STUB(StubGuardrailClient, 즉시 ALLOW)");
            guardrailReport.put("policyVersion", harness.registry.resources().guardrail().version());
            guardrailReport.put("jevBudget", harness.properties.timing().guardrail().jevBudget().toString());
            guardrailReport.put("sendChildName", harness.jev.sendChildName());
            if (mode == GuardrailMode.REAL) {
                // 운영 기동 점검(RealtimeStartupChecks → GuardrailStartupRules, demo 프로필)과 같은 규칙. 사유는 키 이름 · 사유 코드뿐이다
                List<String> startup = GuardrailStartupRules.problems(new GuardrailStartupRules.Inputs(GuardrailMode.REAL.name(),
                        present(env, JEV_API_KEY_ENV), harness.jev.baseUrl(), harness.jev.model(),
                        present(env, JEV_PASS_THRESHOLD_ENV) ? env.apply(JEV_PASS_THRESHOLD_ENV).strip() : null), true, "demo");
                guardrailReport.put("startupProblems", startup);
                problems.addAll(startup);
                this.guardrail = new JevGuardrailClient(harness.jev, JevHosts.modeFor("demo"), () -> source.apply(JEV_API_KEY_ENV),
                        harness::jevQuestion, harness.properties.timing().guardrail().jevBudget(), harness.clock, JSON);
            } else {
                this.guardrail = new StubGuardrailClient();
            }
            this.evidence = mode == GuardrailMode.REAL ? EVIDENCE_REAL_JEV : EVIDENCE_STUB_JEV;
            this.evidenceLabel = mode == GuardrailMode.REAL ? LABEL_REAL_JEV : LABEL_STUB_JEV;
            String ttsModel = blankToDefault(env.apply(TTS_MODEL_ENV), DEFAULT_TTS_MODEL);
            String ttsVoice = blankToDefault(env.apply(TTS_VOICE_ENV), DEFAULT_TTS_VOICE);
            this.speech = new PracticeVoiceChild.OpenAiSpeech(harness.luna.baseUrl(), strict, () -> source.apply(LunaTransport.API_KEY_ENV),
                    ttsModel, ttsVoice);
            boolean childModelFromEnv = present(env, CHILD_MODEL_ENV);
            String childModel = childModelFromEnv ? env.apply(CHILD_MODEL_ENV).strip() : harness.luna.model();
            this.lines = new PracticeVoiceChild.ResponsesWriter(harness.luna.baseUrl(), strict, () -> source.apply(LunaTransport.API_KEY_ENV),
                    childModel, childModelFromEnv ? "env " + CHILD_MODEL_ENV : "운영 Luna 모델(application.yml mujoong.luna.model)");
        }

        @Override
        public String evidence() {
            return evidence;
        }

        @Override
        public String evidenceLabel() {
            return evidenceLabel;
        }

        @Override
        public String providerProfile() {
            return "demo";
        }

        @Override
        public ProviderPort openProvider(RealtimeProperties properties) {
            return new ProviderClient(providerUrl, env.apply(ProviderClient.API_KEY), JSON, properties.provider().connectTimeout());
        }

        @Override
        public void closeProvider(ProviderPort port) {
            if (port instanceof ProviderClient client) {
                client.destroy();
            }
        }

        @Override
        public JudgeClient judge(PracticeVoiceHarness harness, EventLogger events) {
            RealtimeLunaClient client = new RealtimeLunaClient(harness.luna, harness.properties.timing().judgment().liveBudget(),
                    harness.lunaSchemas, new LunaInputSanitizer(JSON), transport, events, harness.clock, JSON);
            return new DecisionsJudgeClient(client::decide, harness.prompt, new JudgeOutcomeValidator(
                    harness.properties.judgment().minConfidence(), harness.properties.judgment().minMargin()), harness.clock);
        }

        @Override
        public GuardrailClient guardrail() {
            return guardrail;
        }

        @Override
        public Map<String, Object> guardrailReport() {
            return guardrailReport;
        }

        @Override
        public PracticeVoiceChild.Speech speech() {
            return speech;
        }

        @Override
        public PracticeVoiceChild.LineWriter lines() {
            return lines;
        }

        @Override
        public List<String> problems() {
            return List.copyOf(problems);
        }

        @Override
        public void close() {
            PracticeVoiceSession.closeQuietly(speech);
            PracticeVoiceSession.closeQuietly(lines);
            PracticeVoiceSession.closeQuietly(transport);
        }
    }

    static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
