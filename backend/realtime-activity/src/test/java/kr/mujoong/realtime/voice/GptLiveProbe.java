package kr.mujoong.realtime.voice;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import kr.mujoong.contract.audio.AudioFrameCodec;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.realtime.dialog.DialogInstructions;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderPort.ProviderSession;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * GPT-Live 격리 실호출 확인 도구 (U11 FR9.1 · FR9.2 · FR12.6 · FR12.7 · BR9.1 · BR9.2 · 기능 설계 W7, U9-c와 같은 방식).
 *
 * <p>사람이 env {@code OPENAI_API_KEY}를 넣고 {@code ./gradlew :realtime-activity:gptLiveTest}로만 부른다. 기본 test · check · CI ·
 * verify.sh에서는 빠진다(build-logic 고정 제외 + 루트 {@code verifyGptLiveIsolation}). 키가 없으면 연결하지 않고
 * {@code SKIPPED_NO_KEY}를 보고하며 이것은 통과가 아니다. 시도하는 방식은 <b>하나</b>다: 운영 송수신 부품 그대로 — 요청은
 * {@link ProviderRequestFactory}, 송신은 {@link ProviderClient.Session}, 해석은 {@link ProviderClient.Reader}. 옛 키트 형식 비교 모드는 없앴다.
 *
 * <p>한 실행의 흐름(추가 전용 실험, U11 Step 12 입력): 연결 → {@code session.start}(표본율 env {@value #RATE_ENV}, 기본 24000) → 합성 말소리 발화와
 * 지시 추가({@code session.instructions.append}) 실험 단계 P1 ~ P4(+ 선택 P5)를 단계마다 2초 이상 조용한 틈을 두고 차례로 → {@code session.close}.
 * <b>{@code response.create}는 보내지 않는다</b>(클라이언트 위임 세션은 거절한다고 실호출로 관측됐다). 지시 추가 메시지는 운영 팩토리가 아니라 이 도구가
 * 원시 JSON({@code event_id} · {@code delegation_id: null} · {@code content})으로 만든다 — 운영 팩토리 수정은 이 실험 결과가 나온 뒤 따로 한다.
 * 지시가 먹혔는지는 전사 원문을 남기지 않고 보려고 <b>표식 낱말</b>(P2 {@value #MARKER_P2}, P3 {@value #MARKER_P3})로 가린다: 지시문이 "응답의 첫 낱말을
 * 표식으로 시작하라"고 시키고, 도구가 출력 전사에 표식이 있는지를 메모리에서만 확인해 예 · 아니오와 개수 · 시간만 남긴다.
 * <ul>
 *   <li>P1 기준선: 발화 하나, 자동 응답의 첫 조각 지연 · 조각 수 · 응답 안 최대 쉼.
 *   <li>P2 끼어들기: 발화를 보내고 첫 출력 조각 뒤 약 300ms에 지시 #1 — 세션 오디오 시계(누적 출력 바이트 / 48 = ms)로 확인 사건 · 표식 전사 ·
 *       조각의 위치를 맞춰 낡은 꼬리와 새 응답의 경계 규칙 R1(확인 사건 도착 뒤 조각) · R2(시계 &gt;= 확인 사건 end_ms)를 표식 기준과 견준다.
 *   <li>P3 유휴 추가: 출력이 없을 때 지시 #2만 — 출력과 전사가 모두 조용해진 뒤({@value #QUIET_ENV}, 기본 4초, 상한 30초)에야 창을 닫는다.
 *   <li>P4 지속성: 평범한 발화를 {@value #PLAIN_TURNS_ENV}번(기본 2) 사이에 조용한 틈을 두고 — 전사의 {@code start_ms}로 귀속해 P3 표식이 남는지.
 *   <li>P6 한 번뿐 지시(env {@value #ONE_SHOT_ENV}, 기본 켬): "이번 한 번만" 지시 뒤 평범한 발화에 표식이 없는지.
 * </ul>
 * 사람이 이 결과로 계속 · 중단을 정한다(BR9.2).
 *
 * <p>연결 열기는 {@link ProviderClient#open}과 같은 방식(Bearer 헤더 · JVM 기본 프록시)이고, 주소는 demo 프로필 파일의
 * {@code mujoong.provider.url}이며 {@link ProviderHosts} demo 규칙을 통과해야만 연결한다(규칙 우회 없음). 입력은 합성 음성만: env
 * {@value #INPUT_PCM_ENV}(WAV 또는 PCM16LE 모노 원시 파일, 원시면 표본율 env {@value #INPUT_RATE_ENV}, 기본 24000), 없으면 저장소 합성음 자리
 * 파일({@value #DEFAULT_INPUT}). 표본율 변환은 이 도구의 선형 보간(새 의존성 없음).
 *
 * <p>보고서에는 항목 · 관측(개수 · 시간 · type 이름 · 키 이름 · 가린 오류 문장) · 판정만 쓴다. 전사 원문 · 음성 · 키 · 주소는 쓰지 않는다
 * (R-F3 · R-F14).
 */
final class GptLiveProbe {

    /** 사람이 주는 합성 입력 음성 파일 경로 env 이름(비밀 아님). */
    static final String INPUT_PCM_ENV = "GPT_LIVE_INPUT_PCM";
    /** 원시 PCM 입력 파일의 표본율 env 이름(비밀 아님, 기본 24000). */
    static final String INPUT_RATE_ENV = "GPT_LIVE_INPUT_RATE";
    /** 세션 시작에 싣는 입력 표본율 env 이름(비밀 아님, 기본 24000 — 24kHz 수락 확인 W7 ①). */
    static final String RATE_ENV = "GPT_LIVE_RATE";
    /** 단계 끝을 정하는 조용한 시간(초) env 이름(비밀 아님, 기본 4, 2 ~ 10). */
    static final String QUIET_ENV = "GPT_LIVE_QUIET_SECONDS";
    /** 지속성 시험의 평범한 발화 횟수 env 이름(비밀 아님, 기본 2, 1 ~ 3). */
    static final String PLAIN_TURNS_ENV = "GPT_LIVE_PLAIN_TURNS";
    /** 한 번뿐 지시 시험을 켜는지 env 이름(비밀 아님, 기본 켬, 0 / false / off면 끔). */
    static final String ONE_SHOT_ENV = "GPT_LIVE_ONE_SHOT";
    /** 보고서 머리 문장(금액을 계산하지 않는다). */
    static final String PRICE_NOTE = "단가는 OpenAI 결제 화면에서 확인한다(이 보고서는 사용량만 남긴다).";
    /** 보고서 경로 시스템 속성(gptLiveTest 작업이 build/gpt-live/report.md로 넣는다). */
    static final String REPORT_PROPERTY = "mujoong.gpt.live.report";
    /** 기본 입력: 저장소의 합성음 자리 파일(저장소 루트 기준). */
    static final String DEFAULT_INPUT = "infra/seed-audio/intro-free-talk-v2.wav";
    /** 공급자 주소가 있는 프로필 파일(실제 주소 기본값은 demo · prod 파일에만 있다, ai-io.md 2절). */
    static final String DEMO_PROFILE_RESOURCE = "application-demo.yml";
    static final String PROVIDER_URL_KEY = "mujoong.provider.url";

    /** 기본 표본율(J27). */
    static final int APP_RATE = ProviderRequestFactory.AUDIO_RATE;
    /** 기기 프레임 한 개(20ms, 24kHz) 바이트 수. */
    static final int FRAME_BYTES = AudioFrameCodec.INPUT_PAYLOAD_BYTES;
    /** 말 끝 신호가 나오게 입력 뒤에 붙이는 무음 프레임 수(1.5초). */
    static final int TRAILING_SILENCE_FRAMES = 75;
    /** P2(끼어들기) 표식 낱말. 드문 낱말이라 평범한 응답에는 나오지 않는다. */
    static final String MARKER_P2 = "나비";
    /** P3(유휴 추가) 표식 낱말. */
    static final String MARKER_P3 = "두루미";
    /** P2 지시 #1(고정 문장). 하던 말을 멈추고 표식으로 시작해 새로 대답하게 시킨다. */
    static final String INSTRUCTION_P2 =
            "지금 하던 말은 멈추고, 방금 아이가 한 말에 다시 대답해. 이번 대답은 맨 처음 낱말을 반드시 '" + MARKER_P2 + "'로 시작하고 그 뒤에 짧게 이어서 말해.";
    /** P3 지시 #2(고정 문장). 말을 시작할 때마다 표식으로 시작하게 시켜서 P4에서 유지 여부를 본다. */
    static final String INSTRUCTION_P3 =
            "이제부터 네가 말을 시작할 때마다 맨 처음 낱말을 반드시 '" + MARKER_P3 + "'로 시작해. 이 규칙은 앞으로 계속 지켜.";
    /** P6(한 번뿐) 표식 낱말. */
    static final String MARKER_P6 = "거북이";
    /** P6 지시(고정 문장): 이번 한 번만 표식으로 시작하고 이후에는 평소처럼. */
    static final String INSTRUCTION_P6 =
            "이번 한 번만 네가 하는 말의 맨 처음 낱말을 '" + MARKER_P6 + "'로 시작해. 그 다음부터는 '" + MARKER_P6 + "'로 시작하지 말고 평소처럼 말해.";
    static final String EVENT_ID_P6 = "gl-p6-append";
    /** 지시 추가 메시지의 사건 id(공급자가 되돌려 주는 client_event_id와 맞춰 본다). */
    static final String EVENT_ID_P2 = "gl-p2-append";
    static final String EVENT_ID_P3 = "gl-p3-append";
    /** 지시 추가의 확인 사건 type 이름 조각(공급자 문서: {@code session.instructions.appended}). */
    static final String APPENDED_MARK = "instructions.append";
    /** 오류 문장을 보고서에 남기는 최대 길이. */
    static final int ERROR_MESSAGE_MAX = 200;

    // 실호출 시험 전용 대기 시간(운영 코드가 아니라 TimeLiteralScanTest 대상 밖). 연결 시한은 운영 값 mujoong.provider.connect-timeout과 같다
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    /** 기다리는 시간 묶음(시험이 줄여 넣을 수 있다). */
    record Timing(Duration framePace, Duration startedWait, Duration settleQuiet, Duration noOutputQuiet, Duration phaseWait,
            Duration closeWait, Duration poll, int trailingSilenceFrames, Duration interruptDelay) {

        /**
         * 실호출 기본: 20ms 간격 · 시작 확인 10초 · 출력과 전사가 4초 조용해지면 단계 끝(단계 사이 틈) · 출력이 없으면 6초 · 한 단계 30초 · 닫힘 5초 · 첫 출력 조각 뒤
         * 300ms에 끼어들기 지시.
         */
        static final Timing REAL = new Timing(Duration.ofMillis(20), Duration.ofSeconds(10), Duration.ofSeconds(4),
                Duration.ofSeconds(6), Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMillis(100),
                TRAILING_SILENCE_FRAMES, Duration.ofMillis(300));

        /** 조용한 시간만 바꾼 사본. */
        Timing withSettleQuiet(Duration quiet) {
            return new Timing(framePace, startedWait, quiet, noOutputQuiet, phaseWait, closeWait, poll, trailingSilenceFrames, interruptDelay);
        }
    }

    /** 앱 해석기({@link ProviderClient#parse})가 아는 type. */
    static final Set<String> KNOWN_TYPES = Set.of("session.started", "session.input_audio.speech_stopped",
            "session.input_transcript.delta", "session.input_transcript.done", "session.output_transcript.delta",
            "session.output_audio.delta", "session.usage.updated", "error", "session.closed");
    private static final Set<String> OUTPUT_AUDIO_TYPES = Set.of("session.output_audio.delta");
    /** 소켓 끊김을 원시 흐름에 넣는 표시 type. */
    static final String CLOSED_MARK = "(소켓 닫힘)";

    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");
    private static final Pattern SAFE_PARAM = Pattern.compile("[A-Za-z0-9_.\\-\\[\\]]{1,80}");
    private static final Pattern MASK_KEY = Pattern.compile("(?i)(sk-[A-Za-z0-9_\\-]{4,}|bearer\\s+\\S+)");
    private static final Pattern MASK_URL = Pattern.compile("(?i)\\b(wss?|https?)://\\S+");
    private static final Pattern MASK_HOST = Pattern.compile("(?i)\\b[a-z0-9-]+(\\.[a-z0-9-]+)*\\.(com|net|org|io|ai|kr|dev|app)\\b");
    private static final Pattern MASK_QUOTED = Pattern.compile("\"[^\"]*\"|'[^']*'|“[^”]*”|‘[^’]*’");
    /** 사건 type 이름 모양(소문자 · 숫자 · 밑줄, 점으로 둘 이상 이음). */
    static final Pattern TYPE_NAME = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$");
    /** 따옴표로 시작하는 type 이름과 그 끝(닫는 따옴표 · 말줄임 · 문장 끝 = 잘림). */
    private static final Pattern QUOTED_TYPE =
            Pattern.compile("['\"‘“]([a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)+)(['\"’”]|…|\\.\\.\\.|$)");
    private static final Pattern BASE64 = Pattern.compile("^[A-Za-z0-9+/]+={0,2}$");
    private static final Pattern MASK_HANGUL = Pattern.compile("[\\uAC00-\\uD7A3\\u3131-\\u318E]+([\\s,.!?]*[\\uAC00-\\uD7A3\\u3131-\\u318E]+)*");

    /** 실행 결과(① · ⑦ 기준, BR9.2). */
    enum Outcome {
        PASS,
        FAIL,
        SKIPPED_NO_KEY
    }

    /** 항목 하나의 판정. INFO는 통과 · 실패가 아니라 다음 단계 입력이다. */
    enum Verdict {
        PASS,
        FAIL,
        NOT_OBSERVED,
        INFO,
        SKIPPED_NO_KEY
    }

    /** 보고서 칸 하나. 관측에는 원문을 넣지 않는다. */
    record Row(String item, String observation, Verdict verdict) {
    }

    /** 실행 결과: 맨 위 outcome은 ① 입력 표본율 수락과 ⑦ append + create 뒤 응답 기준이다. */
    record Result(Outcome outcome, Row hostRule, List<Row> rows, int rate) {
    }

    /** 합성 입력 음성(원래 표본율의 PCM16LE 모노). */
    record InputAudio(String sourceLabel, boolean fromEnv, int sampleRate, byte[] pcm) {

        long millis() {
            return pcm.length / 2 * 1000L / sampleRate;
        }

        /** 표본율을 맞춰 프레임으로 나누고 뒤에 무음 프레임을 붙인다. */
        List<byte[]> framesAt(int rate, int frameBytes) {
            return frames(resample(pcm, sampleRate, rate), frameBytes);
        }

        /** 위와 같되 뒤에 붙이는 무음 프레임 수를 지정한다. */
        List<byte[]> framesAt(int rate, int frameBytes, int trailingSilence) {
            return frames(resample(pcm, sampleRate, rate), frameBytes, trailingSilence);
        }
    }

    /** 연결 열기(시험에서 바꿔 끼운다: 키 없음 시험은 이것이 불리지 않음을 단언한다). */
    @FunctionalInterface
    interface Opener {

        Link open(URI url, String apiKey, Tap tap) throws Exception;
    }

    /** 열린 연결: 운영 {@link ProviderClient.Session}과 그 아래 웹소켓. */
    record Link(WebSocket socket, ProviderSession session, AutoCloseable resources) {
    }

    private final Function<String, String> env;
    private final Path repoRoot;
    private final Path report;
    private final Opener opener;
    private final Timing timing;
    private final JsonCodec json = JsonCodec.shared();
    private final ProviderRequestFactory factory = new ProviderRequestFactory();

    GptLiveProbe(Function<String, String> env, Path repoRoot, Path report, Opener opener) {
        this(env, repoRoot, report, opener, Timing.REAL);
    }

    GptLiveProbe(Function<String, String> env, Path repoRoot, Path report, Opener opener, Timing timing) {
        this.env = env;
        this.repoRoot = repoRoot;
        this.report = report;
        this.opener = opener;
        this.timing = timing;
    }

    /** 실제 실행용: 프로세스 env · 시스템 속성의 저장소 루트 · 보고서 경로, 운영과 같은 방식의 연결 열기. */
    static GptLiveProbe live() {
        Path repoRoot = Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();
        String configured = System.getProperty(REPORT_PROPERTY);
        Path report = Path.of(configured != null ? configured : "build/gpt-live/report.md");
        return new GptLiveProbe(System::getenv, repoRoot, report, GptLiveProbe::openLikeProviderClient);
    }

    Path report() {
        return report;
    }

    /** 확인을 돌리고 보고서를 쓴다. 키가 없으면 연결하지 않는다. */
    Result run() throws IOException {
        String key = env.apply(ProviderClient.API_KEY);
        if (key == null || key.isBlank()) {
            Result skipped = skipped();
            writeReport(skipped, null);
            return skipped;
        }
        int rate = sessionRate(env.apply(RATE_ENV));
        Duration quiet = quietSeconds(env.apply(QUIET_ENV));
        Plan plan = new Plan(plainTurns(env.apply(PLAIN_TURNS_ENV)), oneShot(env.apply(ONE_SHOT_ENV)));
        URI url = providerUrl();
        Optional<ProviderHosts.Problem> problem = ProviderHosts.check(url, "demo");
        if (problem.isPresent()) {
            Row host = new Row("공급자 주소 규칙", "demo 규칙(ProviderHosts) 위반: " + problem.get(), Verdict.FAIL);
            Result blocked = new Result(Outcome.FAIL, host, ITEMS.stream().map(item -> new Row(item, "주소 규칙 위반으로 연결 안 함",
                    Verdict.FAIL)).toList(), rate);
            writeReport(blocked, null);
            return blocked;
        }
        Row host = new Row("공급자 주소 규칙", "demo 프로필 주소가 ProviderHosts demo 규칙(wss · 허용 호스트) 통과", Verdict.PASS);
        InputAudio input = loadInput();
        GptLiveProbe configured = quiet == null ? this
                : new GptLiveProbe(env, repoRoot, report, opener, timing.withSettleQuiet(quiet));
        Result result = configured.attempt(host, url, key, input, rate, plan);
        writeReport(result, input);
        return result;
    }

    /** 세션 시작 표본율(env {@value #RATE_ENV}, 없으면 24000). 틀린 값이면 부르기 전에 거절한다. */
    static int sessionRate(String value) throws IOException {
        if (value == null || value.isBlank()) {
            return APP_RATE;
        }
        try {
            int rate = Integer.parseInt(value.strip());
            if (rate < 8_000 || rate > 48_000) {
                throw new IOException(RATE_ENV + "는 8000 ~ 48000이다");
            }
            return rate;
        } catch (NumberFormatException e) {
            throw new IOException(RATE_ENV + "가 정수가 아니다", e);
        }
    }

    /** 실행 계획: 지속성 시험의 평범한 발화 횟수와 한 번뿐 지시 시험 여부. */
    record Plan(int plainTurns, boolean oneShot) {
    }

    /** 조용한 시간 env(초). 없으면 null(시간 묶음 기본값). */
    static Duration quietSeconds(String value) throws IOException {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int seconds = Integer.parseInt(value.strip());
            if (seconds < 2 || seconds > 10) {
                throw new IOException(QUIET_ENV + "는 2 ~ 10이다");
            }
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            throw new IOException(QUIET_ENV + "가 정수가 아니다", e);
        }
    }

    /** 평범한 발화 횟수 env(기본 2, 1 ~ 3). */
    static int plainTurns(String value) throws IOException {
        if (value == null || value.isBlank()) {
            return 2;
        }
        try {
            int count = Integer.parseInt(value.strip());
            if (count < 1 || count > 3) {
                throw new IOException(PLAIN_TURNS_ENV + "는 1 ~ 3이다");
            }
            return count;
        } catch (NumberFormatException e) {
            throw new IOException(PLAIN_TURNS_ENV + "가 정수가 아니다", e);
        }
    }

    /** 한 번뿐 지시 시험 여부 env(기본 켬). */
    static boolean oneShot(String value) {
        return value == null || value.isBlank() || !Set.of("0", "false", "off", "no").contains(value.strip().toLowerCase(Locale.ROOT));
    }

    /** 지시 추가 수락 행의 위치(outcome 기준). */
    static final int ACCEPT_ROW = 9;

    /** 보고서 행 순서. */
    static final List<String> ITEMS = List.of("① 입력 표본율 수락(세션 시작 · 음성 송신 · 출력 수신)",
            "② 사건 이름(입력 전사 · 출력 전사 · 말 끝 · 위임 · 응답 경계 후보)", "P1 기준선: 자동 응답(첫 조각 지연 · 조각 수 · 응답 안 쉼)",
            "P2 끼어들기: 시간표 귀속(확인 사건 · 표식 전사 · 조각 위치)", "P2 경계 규칙 R1 · R2 대 표식 기준",
            "P3 유휴 추가: 출력 · 전사가 조용해진 뒤 본 결과", "P4 지속성: 평범한 발화 뒤 P3 표식(start_ms 귀속)",
            "P6 한 번뿐 지시: 다음 평범한 발화에 표식이 없는가", "전사 도착 지연(전사 end_ms 대 오디오 시계, 단계별)",
            "지시 추가 수락(오류 · appended 사건 모양 · client_event_id · 지시문 길이)",
            "response.create 미사용 · 지원 목록 밖 type 오류", "종료 형식(session.close → session.closed)", "오류 사건(error)",
            "지원 type 목록(오류 문장에서)", "수신 사건 모양(type별 첫 사건, 키 · 값 타입)", "연결 끊김 · 끊기기 전 마지막 사건",
            "앱 해석기(ProviderClient.Reader) 이해", "참고: 앱이 모르는 type", "사용량(session.usage.updated 마지막 값 · 세션 길이)");

    /** 키가 없을 때의 결과(모든 항목 SKIPPED_NO_KEY, 통과 아님). */
    static Result skipped() {
        Row host = new Row("공급자 주소 규칙", "실호출 안 함(env OPENAI_API_KEY 없음)", Verdict.SKIPPED_NO_KEY);
        List<Row> rows = ITEMS.stream()
                .map(item -> new Row(item, "실호출 안 함(env OPENAI_API_KEY 없음)", Verdict.SKIPPED_NO_KEY)).toList();
        return new Result(Outcome.SKIPPED_NO_KEY, host, rows, 0);
    }

    // ---- 입력 음성 ----

    /** env 파일이 있으면 그것을, 없으면 저장소 합성음 자리 파일을 PCM16LE 모노로 읽는다(표본율은 파일 그대로). */
    InputAudio loadInput() throws IOException {
        String configured = env.apply(INPUT_PCM_ENV);
        if (configured != null && !configured.isBlank()) {
            Path file = Path.of(configured.strip());
            int rawRate = rawRate(env.apply(INPUT_RATE_ENV));
            PcmData data = readPcm16Mono(file, rawRate);
            return new InputAudio("env " + INPUT_PCM_ENV + " 파일(" + extensionOf(file) + " · " + data.rate() + "Hz)", true,
                    data.rate(), data.pcm());
        }
        PcmData data = readPcm16Mono(repoRoot.resolve(DEFAULT_INPUT), APP_RATE);
        return new InputAudio("저장소 합성음 자리 파일 " + DEFAULT_INPUT + "(합성 무음 · 톤)", false, data.rate(), data.pcm());
    }

    /** 원시 PCM 표본율 env 값(없으면 24000). */
    static int rawRate(String value) throws IOException {
        if (value == null || value.isBlank()) {
            return APP_RATE;
        }
        try {
            int rate = Integer.parseInt(value.strip());
            if (rate < 8_000 || rate > 48_000) {
                throw new IOException(INPUT_RATE_ENV + "는 8000 ~ 48000이다");
            }
            return rate;
        } catch (NumberFormatException e) {
            throw new IOException(INPUT_RATE_ENV + "가 정수가 아니다", e);
        }
    }

    /** 읽은 PCM과 표본율. */
    record PcmData(int rate, byte[] pcm) {
    }

    /** WAV면 형식을 읽어 16비트 모노 LE로(JDK 변환), 그 밖은 주어진 표본율의 PCM16LE 모노 원시 바이트로 본다. */
    static PcmData readPcm16Mono(Path file, int rawRate) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("입력 음성 파일이 없다(" + extensionOf(file) + ")");
        }
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav")) {
            byte[] raw = Files.readAllBytes(file);
            if (raw.length == 0 || raw.length % 2 != 0) {
                throw new IOException("원시 PCM16 파일 길이가 0이거나 홀수다");
            }
            return new PcmData(rawRate, raw);
        }
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat source = in.getFormat();
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, source.getSampleRate(), 16, 1, 2,
                    source.getSampleRate(), false);
            if (source.matches(target)) {
                return new PcmData(Math.round(source.getSampleRate()), in.readAllBytes());
            }
            if (!AudioSystem.isConversionSupported(target, source)) {
                throw new IOException("PCM16LE 모노로 바꿀 수 없는 WAV 형식이다(" + source.getEncoding() + " · "
                        + source.getSampleRate() + "Hz · " + source.getChannels() + "ch)");
            }
            try (AudioInputStream converted = AudioSystem.getAudioInputStream(target, in)) {
                return new PcmData(Math.round(source.getSampleRate()), converted.readAllBytes());
            }
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("WAV로 읽을 수 없는 파일이다", e);
        }
    }

    /** PCM16LE 모노 표본율 변환(내릴 때는 이동 평균 뒤 선형 보간, 키트와 같은 방식). 같으면 그대로. */
    static byte[] resample(byte[] pcm, int fromRate, int toRate) {
        if (fromRate == toRate) {
            return pcm.clone();
        }
        ByteBuffer in = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        int n = pcm.length / 2;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = in.getShort(i * 2);
        }
        if (toRate < fromRate) {
            int k = (int) Math.round(fromRate / (double) toRate);
            if (k > 1) {
                double[] smoothed = new double[n];
                for (int i = 0; i < n; i++) {
                    double sum = 0;
                    int count = 0;
                    for (int j = i - k / 2; j < i - k / 2 + k; j++) {
                        if (j >= 0 && j < n) {
                            sum += x[j];
                            count++;
                        }
                    }
                    smoothed[i] = sum / count;
                }
                x = smoothed;
            }
        }
        int outCount = (int) Math.round(n * (double) toRate / fromRate);
        ByteBuffer out = ByteBuffer.allocate(outCount * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < outCount; i++) {
            double position = i * (double) fromRate / toRate;
            int left = (int) Math.floor(position);
            double value;
            if (left >= n - 1) {
                value = n == 0 ? 0 : x[n - 1];
            } else {
                double fraction = position - left;
                value = x[left] * (1 - fraction) + x[left + 1] * fraction;
            }
            out.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value))));
        }
        return out.array();
    }

    /** 앱 형식 960바이트 프레임(아래 {@link #frames(byte[], int)}). */
    static List<byte[]> frames(byte[] pcm) {
        return frames(pcm, FRAME_BYTES);
    }

    /** 프레임으로 나누고(마지막은 0으로 채움) 말 끝 신호용 무음 프레임을 붙인다. */
    static List<byte[]> frames(byte[] pcm, int frameBytes) {
        return frames(pcm, frameBytes, TRAILING_SILENCE_FRAMES);
    }

    /** 위와 같되 뒤에 붙이는 무음 프레임 수를 지정한다. */
    static List<byte[]> frames(byte[] pcm, int frameBytes, int trailingSilence) {
        List<byte[]> frames = new ArrayList<>();
        for (int offset = 0; offset < pcm.length; offset += frameBytes) {
            byte[] frame = new byte[frameBytes];
            System.arraycopy(pcm, offset, frame, 0, Math.min(frameBytes, pcm.length - offset));
            frames.add(frame);
        }
        for (int i = 0; i < trailingSilence; i++) {
            frames.add(new byte[frameBytes]);
        }
        return frames;
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "확장자 없음" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ---- 공급자 주소 ----

    /** demo 프로필 파일의 공급자 주소(코드에 주소를 적지 않는다, R-F3). */
    static URI providerUrl() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(DEMO_PROFILE_RESOURCE,
                new ClassPathResource(DEMO_PROFILE_RESOURCE));
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(PROVIDER_URL_KEY);
            if (value != null && !value.toString().isBlank()) {
                return URI.create(value.toString().strip());
            }
        }
        throw new IOException(DEMO_PROFILE_RESOURCE + "에 " + PROVIDER_URL_KEY + "가 없다");
    }

    // ---- 연결 ----

    /**
     * {@link ProviderClient#open}과 같은 방식으로 연다(JDK HttpClient · 가상 스레드 · JVM 기본 프록시 · Bearer 헤더). 수신은 {@link Tap}이 원시
     * 관측을 적고 운영 {@link ProviderClient.Reader}에 그대로 넘기며, 앱 형식 송신은 운영 {@link ProviderClient.Session}이 한다.
     */
    static Link openLikeProviderClient(URI url, String apiKey, Tap tap) throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpClient.Builder builder = HttpClient.newBuilder().executor(executor);
        ProxySelector proxy = ProxySelector.getDefault();
        if (proxy != null) {
            builder.proxy(proxy);
        }
        HttpClient http = builder.build();
        AutoCloseable resources = () -> {
            http.close();
            executor.shutdownNow();
        };
        try {
            WebSocket socket = http.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey)
                    .buildAsync(url, tap)
                    .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return new Link(socket, new ProviderClient.Session(socket, tap.reader, tap.json, SEND_TIMEOUT), resources);
        } catch (Exception e) {
            resources.close();
            throw e;
        }
    }

    /**
     * 오류 사건 필드(가린 뒤). {@code supportedTypes}는 오류 문장의 "Supported values" 뒤 따옴표 안 type 이름 전부(가림 · 200자 자르기 전
     * 원문에서, 끝이 잘린 이름은 "(잘림)" 표시), {@code rejectedTypes}는 그 앞(거절된 값)의 type 이름이다.
     */
    record ErrorInfo(String type, String code, String param, String message, List<String> supportedTypes, List<String> rejectedTypes) {

        ErrorInfo(String type, String code, String param, String message) {
            this(type, code, param, message, List.of(), List.of());
        }

        String describe() {
            return "type=" + nullText(type) + " code=" + nullText(code) + " param=" + nullText(param) + " message=" + nullText(message);
        }

        private static String nullText(String value) {
            return value == null ? "없음" : value;
        }
    }

    /** 받은 원시 메시지에서 뽑은 것: type 이름 · 응답 id · 키 이름 · 출력 음성 바이트 수 · 오류 필드(값 원문 없음). */
    record RawFrame(int seq, String type, String responseId, List<String> keys, int audioBytes, ErrorInfo error, String shape,
            String usage, long atNanos, int instructionsLength, String clientEventId, long clockMs, long startMs, long endMs) {

        RawFrame(int seq, String type, String responseId, List<String> keys) {
            this(seq, type, responseId, keys, 0, null, "-", null, 0L, -1, null, 0L, -1L, -1L);
        }

        RawFrame(int seq, String type, String responseId, List<String> keys, int audioBytes, ErrorInfo error, String shape,
                String usage) {
            this(seq, type, responseId, keys, audioBytes, error, shape, usage, 0L, -1, null, 0L, -1L, -1L);
        }

        /** 받은 시각(단조 나노초)을 붙인 사본. */
        RawFrame at(long nanos) {
            return new RawFrame(seq, type, responseId, keys, audioBytes, error, shape, usage, nanos, instructionsLength, clientEventId,
                    clockMs, startMs, endMs);
        }

        /** 세션 오디오 시계(이 사건까지 받은 출력 음성의 누적 ms)를 붙인 사본. */
        RawFrame withClock(long clock) {
            return new RawFrame(seq, type, responseId, keys, audioBytes, error, shape, usage, atNanos, instructionsLength, clientEventId,
                    clock, startMs, endMs);
        }

        /** 이 조각(출력 음성)이 시계에서 시작한 위치 ms. */
        long clockStartMs() {
            return clockMs - audioBytes / BYTES_PER_MS;
        }
    }

    /** 24kHz s16 모노 출력 음성의 ms당 바이트(24000 x 2 / 1000). */
    static final long BYTES_PER_MS = 48L;

    /** 사용량을 담는 사건 type(마지막 값을 보고서에 쓴다). */
    static final String USAGE_TYPE = "session.usage.updated";

    /**
     * 사용량 숫자 필드를 이름=값으로(값 원문 중 숫자만). {@code usage} 객체가 있으면 그 안을, 없으면 사건 최상위를 본다. 한 단계 안의 숫자는
     * {@code 바깥.안} 이름. 숫자가 없으면 null.
     */
    static String usageNumbers(Map<?, ?> fields) {
        Map<?, ?> source = fields.get("usage") instanceof Map<?, ?> usage ? usage : fields;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String name = safe(String.valueOf(entry.getKey()));
            if (entry.getValue() instanceof Number number) {
                parts.add(name + "=" + numberText(number));
            } else if (entry.getValue() instanceof Map<?, ?> inner) {
                for (Map.Entry<?, ?> detail : inner.entrySet()) {
                    if (detail.getValue() instanceof Number number) {
                        parts.add(name + "." + safe(String.valueOf(detail.getKey())) + "=" + numberText(number));
                    }
                }
            }
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    private static String numberText(Number number) {
        double value = number.doubleValue();
        return value == Math.rint(value) && !Double.isInfinite(value) ? Long.toString((long) value)
                : String.format(Locale.ROOT, "%.3f", value);
    }

    /** 오류 문장에서 뽑은 type 이름 목록. */
    record TypeLists(List<String> supported, List<String> rejected) {
    }

    /**
     * 오류 문장 원문에서 따옴표 안 type 이름 모양({@link #TYPE_NAME})을 뽑는다. "Supported values" 뒤는 지원 목록, 앞은 거절된 값이다. 끝 따옴표
     * 없이 문장이 끝난 이름은 나온 만큼 "(잘림)"을 붙인다. 중복 제거 · 정렬.
     */
    static TypeLists typeLists(Object value) {
        if (value == null) {
            return new TypeLists(List.of(), List.of());
        }
        String message = String.valueOf(value);
        int marker = message.toLowerCase(Locale.ROOT).indexOf("supported values");
        String head = marker < 0 ? message : message.substring(0, marker);
        String tail = marker < 0 ? "" : message.substring(marker);
        return new TypeLists(quotedTypeNames(tail), quotedTypeNames(head));
    }

    private static List<String> quotedTypeNames(String text) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = QUOTED_TYPE.matcher(text);
        while (matcher.find()) {
            String closing = matcher.group(2);
            boolean complete = closing.equals("'") || closing.equals("\"") || closing.equals("’") || closing.equals("”");
            names.add(complete ? matcher.group(1) : matcher.group(1) + "(잘림)");
        }
        return List.copyOf(names);
    }

    /**
     * 사건 하나의 JSON 모양: 키 이름과 값 타입만(값 없음). 문자열은 base64 모양이면 {@code string(base64)}, 객체는 한 단계 더 펼친다. 예
     * {@code {type:string, response_id:string, delta:string(base64)}}. 해석기 맞춤(FR12.2) 근거이고 원문 · 음성 · 전사는 담지 않는다.
     */
    static String shapeOf(Object value, int depth) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return text.length() >= 16 && text.length() % 4 == 0 && BASE64.matcher(text).matches() ? "string(base64)" : "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() || depth >= 2 ? "array" : "array<" + shapeOf(list.getFirst(), depth + 1) + ">";
        }
        if (value instanceof Map<?, ?> map) {
            if (depth >= 2) {
                return "object";
            }
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                parts.add(safe(String.valueOf(entry.getKey())) + ":" + shapeOf(entry.getValue(), depth + 1));
            }
            return "{" + String.join(", ", parts) + "}";
        }
        return "unknown";
    }

    /**
     * 원시 메시지 한 개를 관측으로 바꾼다. 출력 음성은 {@code audio}(앱 형식) · {@code delta}(문자열 또는 {@code {audio}}, 키트 형식)의 base64
     * 길이만 센다. 오류 사건은 최상위 {@code error} type의 {@code error} 객체와 {@code session.error}의 type · code · param · message를 가려서 남긴다.
     */
    static RawFrame parseRaw(JsonCodec json, int seq, String message) {
        Map<?, ?> fields;
        try {
            fields = json.read(message, Map.class);
        } catch (RuntimeException e) {
            return new RawFrame(seq, "(해석 불가)", null, List.of());
        }
        if (fields == null) {
            return new RawFrame(seq, "(해석 불가)", null, List.of());
        }
        String type = fields.get("type") instanceof String value ? safe(value) : "(type 없음)";
        List<String> keys = fields.keySet().stream().map(String::valueOf).map(GptLiveProbe::safe).sorted().toList();
        String responseId = responseIdOf(fields);
        int audioBytes = type.contains("output_audio") ? audioBytesOf(fields) : 0;
        ErrorInfo error = null;
        if (type.equals("error") || type.equals("session.error")) {
            Map<?, ?> body = fields.get("error") instanceof Map<?, ?> nested ? nested : fields;
            TypeLists lists = typeLists(body.get("message"));
            error = new ErrorInfo(type.equals("error") ? textToken(body.get("type")) : textToken(fields.get("type")),
                    textToken(body.get("code")), paramToken(body.get("param")), maskMessage(body.get("message")), lists.supported(),
                    lists.rejected());
        }
        String usage = type.equals(USAGE_TYPE) || type.equals("session.closed") ? usageNumbers(fields) : null;
        int instructionsLength = fields.get("session") instanceof Map<?, ?> session && session.get("instructions") instanceof String text
                ? text.length() : fields.get("instructions") instanceof String top ? top.length() : -1;
        String clientEventId = fields.get("client_event_id") instanceof String id ? safe(id) : null;
        long startMs = fields.get("start_ms") instanceof Number n ? n.longValue() : -1L;
        long endMs = fields.get("end_ms") instanceof Number n ? n.longValue() : -1L;
        return new RawFrame(seq, type, responseId, keys, audioBytes, error, shapeOf(fields, 0), usage, 0L, instructionsLength, clientEventId,
                0L, startMs, endMs);
    }

    private static String responseIdOf(Map<?, ?> fields) {
        for (String name : List.of("responseId", "response_id")) {
            if (fields.get(name) instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        if (fields.get("response") instanceof Map<?, ?> response && response.get("id") instanceof String value) {
            return value;
        }
        if (fields.get("delta") instanceof Map<?, ?> delta) {
            return responseIdOf(delta);
        }
        return null;
    }

    private static int audioBytesOf(Map<?, ?> fields) {
        Object value = fields.get("audio") != null ? fields.get("audio") : fields.get("delta");
        if (value instanceof Map<?, ?> nested) {
            value = nested.get("audio");
        }
        if (!(value instanceof String text) || text.isEmpty()) {
            return 0;
        }
        try {
            return Base64.getDecoder().decode(text).length;
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private static String textToken(Object value) {
        return value == null ? null : safe(String.valueOf(value));
    }

    private static String paramToken(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return SAFE_PARAM.matcher(text).matches() ? text : "(형식 밖)";
    }

    /**
     * 오류 문장: 앞 200자만, 키 · 주소 · 따옴표 안 값 · 한글 문장(전사 모양)을 가리고 줄바꿈 · 표 구분자를 없앤다(R-F3 · R-F14). 따옴표 안 값이
     * 사건 type 이름 모양({@link #TYPE_NAME}, 예 {@code session.input_audio.append})이면 가리지 않는다.
     */
    static String maskMessage(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        if (text.length() > ERROR_MESSAGE_MAX) {
            text = text.substring(0, ERROR_MESSAGE_MAX) + "…";
        }
        // type 이름 모양의 따옴표 값은 자리표로 빼 두고 나머지를 가린 뒤 되돌린다(다른 가림 규칙이 건드리지 않게)
        List<String> kept = new ArrayList<>();
        Matcher quoted = MASK_QUOTED.matcher(text);
        StringBuilder held = new StringBuilder();
        while (quoted.find()) {
            String whole = quoted.group();
            String inner = whole.substring(1, whole.length() - 1);
            String replacement = whole;
            if (TYPE_NAME.matcher(inner).matches()) {
                kept.add(whole);
                replacement = "\u0001" + (kept.size() - 1) + "\u0001";
            }
            quoted.appendReplacement(held, Matcher.quoteReplacement(replacement));
        }
        quoted.appendTail(held);
        text = held.toString();
        // 한글 문장을 먼저 가린다(뒤 단계의 한글 대체 문구가 다시 가려지지 않게)
        text = MASK_HANGUL.matcher(text).replaceAll("[문장 가림]");
        text = MASK_KEY.matcher(text).replaceAll("[키 가림]");
        text = MASK_URL.matcher(text).replaceAll("[주소 가림]");
        text = MASK_HOST.matcher(text).replaceAll("[주소 가림]");
        text = MASK_QUOTED.matcher(text).replaceAll("[값 가림]");
        for (int i = 0; i < kept.size(); i++) {
            text = text.replace("\u0001" + i + "\u0001", kept.get(i));
        }
        return text.replace('|', '/').replaceAll("[\\r\\n\\t\\u0001]+", " ").strip();
    }

    /**
     * 수신 갈래: 메시지마다 원시 관측({@link #parseRaw})을 적고 운영 {@link ProviderClient.Reader}에 그대로 넘긴다(앱 해석 결과는
     * {@link #events}로). 소켓이 닫히거나 끊기면 닫힘 코드 · 오류 종류와 끊기기 전 마지막 type을 남긴다.
     */
    static final class Tap implements WebSocket.Listener {

        final JsonCodec json;
        final ProviderClient.Reader reader;
        /** 운영 해석기가 만든 앱 사건. */
        final LinkedBlockingQueue<ProviderEvent> events = new LinkedBlockingQueue<>();
        private final List<RawFrame> raw = new ArrayList<>();
        /** 출력 전사 조각 글(메모리에서 표식 낱말 확인에만 쓰고 보고서 · 원시 관측에는 넣지 않는다, R-F14). */
        private final List<TextAt> outputText = new ArrayList<>();
        private long audioBytesTotal;
        private final StringBuilder text = new StringBuilder();
        private volatile Integer closeCode;
        private volatile String socketError;
        private volatile String lastTypeBeforeClose;
        private volatile long startedNanos;
        private volatile long closedNanos;

        Tap(JsonCodec json) {
            this.json = json;
            this.reader = new ProviderClient.Reader(events::add, json);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            reader.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                record(text.toString());
                text.setLength(0);
            }
            return reader.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            add(new RawFrame(rawCount(), "(binary)", null, List.of()));
            return reader.onBinary(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode = statusCode;
            markClosed();
            return reader.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            // close 프레임 없이 끊기면(1006) JDK는 onClose 대신 이것을 부른다. 메시지는 쓰지 않고 종류 이름만 남긴다
            socketError = error == null ? "알 수 없음" : error.getClass().getSimpleName();
            markClosed();
            reader.onError(webSocket, error);
        }

        void record(String message) {
            RawFrame parsed = parseRaw(json, rawCount(), message).at(System.nanoTime());
            synchronized (outputText) {
                audioBytesTotal += parsed.audioBytes();
                RawFrame frame = parsed.withClock(audioBytesTotal / BYTES_PER_MS);
                parsed = frame;
                if (isOutputTranscript(frame.type())) {
                    String piece = transcriptPiece(json, message);
                    if (!piece.isEmpty()) {
                        outputText.add(new TextAt(frame.atNanos(), piece, frame.startMs(), frame.endMs(), frame.clockMs()));
                    }
                }
            }
            RawFrame frame = parsed;
            if (frame.type().equals("session.started") && startedNanos == 0) {
                startedNanos = System.nanoTime();
            } else if (frame.type().equals("session.closed") && closedNanos == 0) {
                closedNanos = System.nanoTime();
            }
            add(frame);
        }

        private void markClosed() {
            synchronized (raw) {
                if (lastTypeBeforeClose == null) {
                    lastTypeBeforeClose = raw.isEmpty() ? "(받은 사건 없음)" : raw.getLast().type();
                }
            }
            if (closedNanos == 0) {
                closedNanos = System.nanoTime();
            }
            add(new RawFrame(rawCount(), CLOSED_MARK, null, List.of()).at(System.nanoTime()));
        }

        /** session.started ~ 닫힘(session.closed 또는 소켓 닫힘) 길이 ms. 둘 중 하나가 없으면 null. */
        Long sessionMillis() {
            return startedNanos == 0 || closedNanos == 0 ? null : TimeUnit.NANOSECONDS.toMillis(closedNanos - startedNanos);
        }

        private void add(RawFrame frame) {
            synchronized (raw) {
                raw.add(frame);
            }
        }

        /**
         * 구간 {@code [fromNanos, toNanos)}에 받은 출력 전사에 표식 낱말이 있는지(공백 무시)를 메모리에서만 본다. 글은 돌려주지 않고 예 · 아니오와
         * 도착 시각 · 표식 앞 글자 수 · 구간 글자 수만 돌려준다.
         */
        MarkerHit findMarker(String marker, long fromNanos, long toNanos) {
            return GptLiveProbe.findMarker(pieces(), marker, piece -> piece.atNanos() >= fromNanos && piece.atNanos() < toNanos);
        }

        /** 지금까지 받은 출력 전사 조각(글은 메모리에서만 쓴다). */
        List<TextAt> pieces() {
            synchronized (outputText) {
                return List.copyOf(outputText);
            }
        }

        /** 마지막 출력 활동(출력 음성 또는 전사 사건)을 받은 시각(없으면 0). 사용량 같은 주기 사건은 세지 않는다. */
        long lastActivityNanos() {
            synchronized (raw) {
                for (int i = raw.size() - 1; i >= 0; i--) {
                    String type = raw.get(i).type();
                    if (OUTPUT_AUDIO_TYPES.contains(type) || type.contains("transcript")) {
                        return raw.get(i).atNanos();
                    }
                }
                return 0L;
            }
        }

        /** 이 시각 뒤 처음 받은 출력 음성 조각 시각(없으면 0). */
        long firstAudioNanosSince(long sinceNanos) {
            synchronized (raw) {
                for (RawFrame frame : raw) {
                    if (OUTPUT_AUDIO_TYPES.contains(frame.type()) && frame.atNanos() >= sinceNanos) {
                        return frame.atNanos();
                    }
                }
                return 0L;
            }
        }

        /** 그 type의 사건을 받은 적이 있는가. */
        boolean has(String type) {
            synchronized (raw) {
                return raw.stream().anyMatch(frame -> frame.type().equals(type));
            }
        }

        /** 소켓이 닫혔거나 {@code session.closed}를 받았는가. */
        boolean closed() {
            return closedNanos != 0;
        }

        /** 마지막 출력 음성 조각을 받은 시각(없으면 0). */
        long lastAudioNanos() {
            synchronized (raw) {
                for (int i = raw.size() - 1; i >= 0; i--) {
                    if (OUTPUT_AUDIO_TYPES.contains(raw.get(i).type())) {
                        return raw.get(i).atNanos();
                    }
                }
                return 0L;
            }
        }

        /** 마지막 사건을 받은 시각(없으면 0). */
        long lastFrameNanos() {
            synchronized (raw) {
                return raw.isEmpty() ? 0L : raw.getLast().atNanos();
            }
        }

        int rawCount() {
            synchronized (raw) {
                return raw.size();
            }
        }

        List<RawFrame> rawSnapshot() {
            synchronized (raw) {
                return List.copyOf(raw);
            }
        }

        Integer closeCode() {
            return closeCode;
        }

        String socketError() {
            return socketError;
        }

        String lastTypeBeforeClose() {
            return lastTypeBeforeClose;
        }
    }

    /** 출력 전사 조각 하나(받은 시각 · 글). */
    record TextAt(long atNanos, String text, long startMs, long endMs, long clockMs) {
    }

    /**
     * 표식 낱말 확인 결과(글 없음).
     *
     * @param found 구간 전사에 표식이 있는가
     * @param foundAtNanos 표식이 완성된 전사 조각을 받은 시각(없으면 0)
     * @param charsBefore 표식 앞의 전사 글자 수(공백 제외, 0이면 구간 전사가 표식으로 시작, 없으면 -1)
     * @param windowChars 구간 전사의 글자 수(공백 제외)
     */
    record MarkerHit(boolean found, long foundAtNanos, int charsBefore, int windowChars, long markerStartMs) {
    }

    /**
     * 조건에 맞는 전사 조각들(도착 순서)에서 표식 낱말을 찾는다(공백 무시, 글은 돌려주지 않는다). {@code markerStartMs}는 표식 첫 글자가 든 조각의
     * {@code start_ms}(없으면 -1)다.
     */
    static MarkerHit findMarker(List<TextAt> pieces, String marker, java.util.function.Predicate<TextAt> in) {
        StringBuilder window = new StringBuilder();
        List<TextAt> owner = new ArrayList<>();
        for (TextAt piece : pieces) {
            if (!in.test(piece)) {
                continue;
            }
            piece.text().codePoints().filter(cp -> !Character.isWhitespace(cp)).forEach(cp -> {
                int before = window.length();
                window.appendCodePoint(cp);
                for (int i = before; i < window.length(); i++) {
                    owner.add(piece);
                }
            });
        }
        String target = marker.replaceAll("\\s+", "");
        int index = window.indexOf(target);
        if (index < 0) {
            return new MarkerHit(false, 0L, -1, window.length(), -1L);
        }
        TextAt first = owner.get(index);
        TextAt last = owner.get(index + target.length() - 1);
        return new MarkerHit(true, last.atNanos(), index, window.length(), first.startMs());
    }

    /** 출력 전사 사건 하나의 글 조각({@code delta}가 문자열이거나 {@code {text}} 객체). 없으면 빈 문자열. */
    static String transcriptPiece(JsonCodec json, String message) {
        try {
            Map<?, ?> fields = json.read(message, Map.class);
            Object delta = fields == null ? null : fields.get("delta");
            if (delta instanceof String text) {
                return text;
            }
            if (delta instanceof Map<?, ?> nested && nested.get("text") instanceof String text) {
                return text;
            }
        } catch (RuntimeException e) {
            // 해석할 수 없는 메시지는 원시 관측이 이미 '(해석 불가)'로 남겼다
        }
        return "";
    }

    static String safe(String token) {
        return token != null && SAFE_TOKEN.matcher(token).matches() ? token : "(형식 밖)";
    }

    // ---- 관측 ----

    /** 한 발화(입력 음성을 보내고 자동 응답을 기다린 구간). 시각은 단조 나노초다. */
    record Utterance(long startNanos, long lastVoicedNanos, long lastSentNanos, long endNanos, int sentFrames, int totalFrames,
            String sendFailure) {
    }

    /** 지시 추가 한 번의 관측(P2 · P3). */
    static final class AppendTrial {

        final String eventId;
        final String content;
        long sentNanos;
        String failure;
        /** P2: 첫 출력 조각을 받은 뒤 보낼 때까지 걸린 ms(P3는 -1). */
        long afterFirstChunkMillis = -1;
        /** P3: 보내기 직전 조용했던 시간 ms(받은 사건이 없으면 -1). */
        long idleMillis = -1;
        long endNanos;

        AppendTrial(String eventId, String content) {
            this.eventId = eventId;
            this.content = content;
        }

        boolean sent() {
            return sentNanos != 0 && failure == null;
        }
    }

    /** 한 실행의 원자료. 보고서 행은 이것과 원시 관측({@link RawFrame})만으로 계산한다({@link #buildRows}). */
    static final class Obs {

        int rate;
        InputAudio input;
        long connectedMillis;
        String startFailure;
        long startedMillis = -1;
        /** 보낸 발화 전부(P1 · P2 · P4 · P5 순서). */
        final List<Utterance> utterances = new ArrayList<>();
        Utterance p1;
        Utterance p2;
        /** P4 평범한 발화들(순서대로). */
        final List<Utterance> plain = new ArrayList<>();
        /** P6 한 번뿐 지시 뒤의 평범한 발화(시험하지 않았으면 null). */
        Utterance p6;
        AppendTrial a1;
        AppendTrial a2;
        AppendTrial a3;
        boolean oneShotPlanned;
        /** 출력 전사 조각(글은 메모리에서 표식 확인에만 쓰고 보고서에는 넣지 않는다). */
        List<TextAt> pieces = List.of();
        boolean closedEarly;
        String closeFailure;
        long closeMillis;
        boolean closedMessage;
        Integer closeCode;
        String socketError;
        String lastTypeBeforeClose;
        Long sessionMillis;
        int interpretedStarted;
        int interpretedAudio;
        long interpretedAudioBytes;
        int interpretedUsage;
        int interpretedErrors;
        final List<RawFrame> raw = new ArrayList<>();

        /** 처음 보낸 지시 추가 시각(없으면 0). */
        long firstAppendNanos() {
            long first = 0L;
            for (AppendTrial trial : new AppendTrial[] {a1, a2, a3}) {
                if (trial != null && trial.sentNanos != 0 && (first == 0L || trial.sentNanos < first)) {
                    first = trial.sentNanos;
                }
            }
            return first;
        }

        /** 지시 추가를 실제로 보낸 사건 id들. */
        List<String> sentEventIds() {
            List<String> ids = new ArrayList<>();
            for (AppendTrial trial : new AppendTrial[] {a1, a2, a3}) {
                if (trial != null && trial.sent()) {
                    ids.add(trial.eventId);
                }
            }
            return ids;
        }
    }

    /** 연결 → 시작 → P1 ~ P4(+ P5) → 종료를 관측한다. */
    Result attempt(Row host, URI url, String key, InputAudio input, int rate, Plan plan) {
        Tap tap = new Tap(json);
        long openStart = System.nanoTime();
        Link link;
        try {
            link = opener.open(url, key, tap);
        } catch (Exception e) {
            return new Result(Outcome.FAIL, host, ITEMS.stream().map(item -> new Row(item, "연결 실패(" + failureName(e) + ")",
                    ITEMS.indexOf(item) == 0 || ITEMS.indexOf(item) == ACCEPT_ROW ? Verdict.FAIL : Verdict.NOT_OBSERVED)).toList(), rate);
        }
        try {
            Obs obs = observe(link, tap, input, rate, plan, openStart);
            List<Row> rows = buildRows(obs);
            boolean one = rows.get(0).verdict() == Verdict.PASS;
            boolean accepted = rows.get(ACCEPT_ROW).verdict() == Verdict.PASS;
            return new Result(one && accepted ? Outcome.PASS : Outcome.FAIL, host, rows, rate);
        } finally {
            link.session().close();
            try {
                link.resources().close();
            } catch (Exception e) {
                // 정리 경로: 연결 자원 닫기 실패는 결과에 영향이 없다
            }
        }
    }

    /** 송신 하나. 실패하면 사유 이름. */
    @FunctionalInterface
    private interface Send {

        void run() throws Exception;
    }

    private static String send(Send action) {
        try {
            action.run();
            return null;
        } catch (Exception e) {
            return failureName(e);
        }
    }

    private void sendText(Link link, String text) throws Exception {
        link.socket().sendText(text, true).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 입력 음성 프레임 하나: 기본 표본율은 운영 팩토리, 그 밖의 표본율(20ms 프레임)은 같은 레코드를 직접 만든다. */
    private ProviderRequestFactory.InputAudioAppend inputAudio(byte[] frame, int rate) {
        if (rate == APP_RATE) {
            return factory.inputAudio(frame);
        }
        return new ProviderRequestFactory.InputAudioAppend(ProviderRequestFactory.TYPE_INPUT_AUDIO_APPEND,
                Base64.getEncoder().encodeToString(frame));
    }

    /** 지시 추가 원시 메시지: {@code delegation_id}는 값이 null이어도 반드시 있어야 한다(공급자 오류로 관측). 운영 팩토리와 무관하다. */
    String appendMessage(String eventId, String content) {
        return "{\"type\":\"session.instructions.append\",\"event_id\":" + json.write(eventId)
                + ",\"delegation_id\":null,\"content\":" + json.write(content) + "}";
    }

    private void sendAppend(Link link, AppendTrial trial) {
        trial.sentNanos = System.nanoTime();
        trial.failure = send(() -> sendText(link, appendMessage(trial.eventId, trial.content)));
    }

    private boolean live(Tap tap, boolean started, Obs obs) {
        return started && obs.startFailure == null && !tap.closed();
    }

    private Obs observe(Link link, Tap tap, InputAudio input, int rate, Plan plan, long openStart) {
        ProviderSession session = link.session();
        Obs obs = new Obs();
        obs.rate = rate;
        obs.input = input;
        obs.oneShotPlanned = plan.oneShot();
        obs.connectedMillis = millisSince(openStart);

        // A. 세션 시작(표본율 지정)
        obs.startFailure = send(() -> session.start(factory.sessionStart(new ChildContext("하늘", 8, List.of("블록")), rate)));
        long startSent = System.nanoTime();
        waitFor(tap, startSent, timing.startedWait(), () -> tap.has("session.started") || tap.closed() || tap.has("error"));
        boolean started = tap.has("session.started");
        obs.startedMillis = started ? millisSince(startSent) : -1;

        int frameBytes = rate / 50 * 2;
        List<byte[]> frames = input.framesAt(rate, frameBytes, timing.trailingSilenceFrames());
        int voiced = frames.size() - timing.trailingSilenceFrames();

        // P1 기준선: 발화 하나 → 자동 응답(각 단계는 조용해진 뒤 끝나므로 단계 사이에 2초 이상 틈이 있다)
        if (live(tap, started, obs)) {
            obs.p1 = speak(session, tap, frames, voiced, rate, start -> { }, () -> 0L);
            obs.utterances.add(obs.p1);
        }

        // P2 끼어들기: 첫 출력 조각 뒤 interruptDelay에 지시 #1
        if (live(tap, started, obs) && obs.p1 != null) {
            AppendTrial a1 = new AppendTrial(EVENT_ID_P2, INSTRUCTION_P2);
            obs.a1 = a1;
            obs.p2 = speak(session, tap, frames, voiced, rate, start -> {
                if (a1.sentNanos != 0 || tap.closed()) {
                    return;
                }
                long first = tap.firstAudioNanosSince(start);
                if (first != 0 && System.nanoTime() - first >= timing.interruptDelay().toNanos()) {
                    a1.afterFirstChunkMillis = millisSince(first);
                    sendAppend(link, a1);
                }
            }, () -> a1.sentNanos == 0 ? 0L : a1.sentNanos + timing.noOutputQuiet().toNanos());
            obs.utterances.add(obs.p2);
            a1.endNanos = System.nanoTime();
        }

        // P3 유휴 추가: 출력이 없을 때(앞 단계가 조용해진 뒤) 지시 #2만 보낸다
        if (live(tap, started, obs)) {
            AppendTrial a2 = new AppendTrial(EVENT_ID_P3, INSTRUCTION_P3);
            obs.a2 = a2;
            long lastFrame = tap.lastActivityNanos();
            a2.idleMillis = lastFrame == 0 ? -1 : millisSince(lastFrame);
            sendAppend(link, a2);
            if (a2.failure == null) {
                settle(tap, a2.sentNanos, () -> { }, () -> 0L);
            }
            a2.endNanos = System.nanoTime();
        }

        // P4 지속성: 평범한 발화를 조용한 틈을 두고 여러 번 → 전사 start_ms로 P3 표식이 남는지(귀속은 보고서 계산에서)
        for (int turn = 0; turn < plan.plainTurns() && live(tap, started, obs); turn++) {
            Utterance plain = speak(session, tap, frames, voiced, rate, start -> { }, () -> 0L);
            obs.plain.add(plain);
            obs.utterances.add(plain);
        }

        // P6 한 번뿐 지시: 출력이 없을 때 지시를 보내고(저절로 표식 말을 하는지), 조용해진 뒤 평범한 발화 하나
        if (plan.oneShot() && live(tap, started, obs)) {
            AppendTrial a3 = new AppendTrial(EVENT_ID_P6, INSTRUCTION_P6);
            obs.a3 = a3;
            long lastFrame = tap.lastActivityNanos();
            a3.idleMillis = lastFrame == 0 ? -1 : millisSince(lastFrame);
            sendAppend(link, a3);
            if (a3.failure == null) {
                settle(tap, a3.sentNanos, () -> { }, () -> 0L);
            }
            a3.endNanos = System.nanoTime();
            if (live(tap, started, obs)) {
                obs.p6 = speak(session, tap, frames, voiced, rate, start -> { }, () -> 0L);
                obs.utterances.add(obs.p6);
            }
        }
        obs.pieces = tap.pieces();

        // 종료 형식
        long closeStart = System.nanoTime();
        obs.closedEarly = tap.closed();
        if (!obs.closedEarly) {
            obs.closeFailure = send(() -> sendText(link, json.write(factory.sessionClose())));
            waitFor(tap, closeStart, timing.closeWait(), tap::closed);
        }
        obs.closeMillis = millisSince(closeStart);
        ProviderPort.ProviderEvent event;
        while ((event = tap.events.poll()) != null) {
            switch (event) {
                case ProviderEvent.Started ignored -> obs.interpretedStarted++;
                case ProviderEvent.OutputAudioDelta delta -> {
                    obs.interpretedAudio++;
                    obs.interpretedAudioBytes += delta.audio().length;
                }
                case ProviderEvent.UsageUpdated ignored -> obs.interpretedUsage++;
                case ProviderEvent.Error ignored -> obs.interpretedErrors++;
                default -> {
                    // 그 밖의 사건은 세지 않는다
                }
            }
        }
        obs.raw.addAll(tap.rawSnapshot());
        obs.closedMessage = obs.raw.stream().anyMatch(frame -> frame.type().equals("session.closed"));
        obs.closeCode = tap.closeCode();
        obs.socketError = tap.socketError();
        obs.lastTypeBeforeClose = tap.lastTypeBeforeClose();
        obs.sessionMillis = tap.sessionMillis();
        return obs;
    }

    /**
     * 발화 하나를 실시간 간격으로 보내고 응답이 끝나기(또는 오지 않기)를 기다린다. {@code tick}은 프레임을 보낸 뒤와 기다리는 동안 계속 불리고
     * (발화 시작 시각을 받는다), {@code floor}는 그 시각 전에는 기다림을 끝내지 않게 한다(0이면 제한 없음).
     */
    private Utterance speak(ProviderSession session, Tap tap, List<byte[]> frames, int voiced, int rate,
            java.util.function.LongConsumer tick, java.util.function.LongSupplier floor) {
        long start = System.nanoTime();
        long lastVoiced = start;
        long lastSent = start;
        int sent = 0;
        String failure = null;
        for (int index = 0; index < frames.size(); index++) {
            byte[] frame = frames.get(index);
            failure = send(() -> session.sendInputAudio(inputAudio(frame, rate)));
            if (failure != null) {
                break;
            }
            sent++;
            lastSent = System.nanoTime();
            if (index < voiced) {
                lastVoiced = lastSent;
            }
            sleep(timing.framePace());
            tick.accept(start);
            if (tap.closed()) {
                failure = "CLOSED";
                break;
            }
        }
        if (failure == null) {
            settle(tap, start, () -> tick.accept(start), floor);
        }
        return new Utterance(start, lastVoiced, lastSent, System.nanoTime(), sent, frames.size(), failure);
    }

    /**
     * 출력이 끝나기를 기다린다: 이 시각 뒤 출력 음성 · 전사 사건이 온 적이 있으면 마지막 그런 사건 뒤 {@code settleQuiet}(기본 4초)가 지날 때까지, 출력이 한 번도 안 오면
     * {@code noOutputQuiet}가 지날 때까지(둘 다 {@code phaseWait} 상한, 연결이 닫히면 끝). {@code floor} 시각 전에는 끝내지 않는다.
     */
    private void settle(Tap tap, long sinceNanos, Runnable tick, java.util.function.LongSupplier floor) {
        long deadline = System.nanoTime() + timing.phaseWait().toNanos();
        long begin = System.nanoTime();
        while (System.nanoTime() < deadline && !tap.closed()) {
            tick.run();
            long now = System.nanoTime();
            long lastActivity = tap.lastActivityNanos();
            boolean heard = lastActivity >= sinceNanos;
            boolean floorPassed = now >= floor.getAsLong();
            if (floorPassed && heard && now - lastActivity >= timing.settleQuiet().toNanos()) {
                return;
            }
            if (floorPassed && !heard && now - begin >= timing.noOutputQuiet().toNanos()) {
                return;
            }
            sleep(timing.poll());
        }
    }

    private void waitFor(Tap tap, long sinceNanos, Duration limit, BooleanSupplier done) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            sleep(timing.poll());
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis(), (int) (duration.toNanos() % 1_000_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("실호출 확인이 중단됐다", e);
        }
    }

    // ---- 행 만들기(원자료 → 보고서 행, 원문 없음) ----

    static boolean isOutputTranscript(String type) {
        return type.contains("transcript") && !type.contains("input");
    }

    static boolean isInputTranscript(String type) {
        return type.contains("transcript") && type.contains("input");
    }

    static boolean isSpeechEnd(String type) {
        return type.contains("speech_stopped") || type.contains("speech_end") || type.contains("end_of_speech");
    }

    private static final Pattern BOUNDARY_WORD = Pattern.compile("(done|complet|finish|ended|created|response|turn|boundary)");

    /** 앱이 모르는 type 중 응답 끝 · 경계처럼 보이는 것(type 이름 · 횟수 · 키 이름, 응답 id 같은 값은 없다). */
    static String boundaryCandidates(List<RawFrame> raw) {
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, List<String>> keys = new LinkedHashMap<>();
        for (RawFrame frame : raw) {
            String type = frame.type();
            if (KNOWN_TYPES.contains(type) || type.equals(CLOSED_MARK) || type.startsWith("(")) {
                continue;
            }
            if (BOUNDARY_WORD.matcher(type).find()) {
                counts.merge(type, 1, Integer::sum);
                keys.putIfAbsent(type, frame.keys());
            }
        }
        if (counts.isEmpty()) {
            return "없음";
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((type, count) -> parts.add(type + " " + count + "건(키 " + keys.get(type) + ")"));
        return String.join(", ", parts);
    }

    private static List<RawFrame> framesOfType(List<RawFrame> raw, java.util.function.Predicate<String> match) {
        return raw.stream().filter(frame -> match.test(frame.type())).toList();
    }

    private static String typeSummary(List<RawFrame> frames) {
        Map<String, Integer> counts = new TreeMap<>();
        frames.forEach(frame -> counts.merge(frame.type(), 1, Integer::sum));
        return counts.toString();
    }

    /** 발화 구간 안의 출력 음성 조각 시각들(단조 나노초). */
    private static List<Long> audioTimes(List<RawFrame> raw, long fromNanos, long toNanos) {
        return raw.stream().filter(frame -> OUTPUT_AUDIO_TYPES.contains(frame.type()) && frame.atNanos() >= fromNanos
                && frame.atNanos() < toNanos).map(RawFrame::atNanos).sorted().toList();
    }

    private static long maxGapMillis(List<Long> times) {
        long max = 0;
        for (int i = 1; i < times.size(); i++) {
            max = Math.max(max, millisBetween(times.get(i - 1), times.get(i)));
        }
        return max;
    }

    private static long countIn(List<RawFrame> raw, java.util.function.Predicate<String> match, long fromNanos, long toNanos) {
        return raw.stream().filter(frame -> match.test(frame.type()) && frame.atNanos() >= fromNanos && frame.atNanos() < toNanos).count();
    }

    /** 구간 안에서 앱이 모르는 type(지시 추가 확인 사건은 따로 보므로 뺀다)의 이름과 횟수. 응답 경계를 알리는 사건이 있는지 본다. */
    static String unknownTypesIn(List<RawFrame> raw, long fromNanos, long toNanos) {
        Map<String, Integer> counts = new TreeMap<>();
        raw.stream().filter(frame -> frame.atNanos() >= fromNanos && frame.atNanos() < toNanos && !KNOWN_TYPES.contains(frame.type())
                && !frame.type().equals(CLOSED_MARK) && !frame.type().contains(APPENDED_MARK))
                .forEach(frame -> counts.merge(frame.type(), 1, Integer::sum));
        return counts.isEmpty() ? "없음" : counts.toString();
    }

    private static String markerText(MarkerHit hit, long sentNanos) {
        if (hit == null) {
            return "확인 못 함";
        }
        if (!hit.found()) {
            return "아니오(구간 전사 " + hit.windowChars() + "자에 표식 없음)";
        }
        return "예(전사 도착 " + (hit.foundAtNanos() == 0 ? "시각 모름" : "+" + millisBetween(sentNanos, hit.foundAtNanos()) + "ms")
                + " · 표식 앞 전사 " + hit.charsBefore() + "자" + (hit.charsBefore() == 0 ? " = 구간 전사가 표식으로 시작" : "") + ")";
    }

    /** 출력 음성 조각 하나의 위치(도착 시각 · 세션 오디오 시계에서 차지한 ms 구간). */
    record Chunk(long atNanos, long clockStartMs, long clockEndMs) {
    }

    static List<Chunk> chunksIn(List<RawFrame> raw, long fromNanos, long toNanos) {
        return raw.stream().filter(frame -> OUTPUT_AUDIO_TYPES.contains(frame.type()) && frame.audioBytes() > 0
                && frame.atNanos() >= fromNanos && frame.atNanos() < toNanos)
                .map(frame -> new Chunk(frame.atNanos(), frame.clockStartMs(), frame.clockMs())).toList();
    }

    /** 단계 창: 도착 시각 범위와 그 안 출력 음성이 오디오 시계에서 차지한 구간(음성이 없으면 -1). */
    record Win(String name, long startNanos, long endNanos, long clockFirstMs, long clockLastMs) {

        boolean hasAudio() {
            return clockFirstMs >= 0;
        }
    }

    static Win winOf(List<RawFrame> raw, String name, long startNanos, long endNanos) {
        List<Chunk> chunks = chunksIn(raw, startNanos, endNanos + 1);
        return new Win(name, startNanos, endNanos, chunks.isEmpty() ? -1 : chunks.getFirst().clockStartMs(),
                chunks.isEmpty() ? -1 : chunks.getLast().clockEndMs());
    }

    /** 실행에서 시험한 단계 창들(P1, P2, P3, P4#n, P6a, P6b). */
    static List<Win> windows(Obs obs) {
        List<RawFrame> raw = obs.raw;
        List<Win> wins = new ArrayList<>();
        if (obs.p1 != null) {
            wins.add(winOf(raw, "P1", obs.p1.startNanos(), obs.p1.endNanos()));
        }
        if (obs.p2 != null) {
            wins.add(winOf(raw, "P2", obs.p2.startNanos(), obs.p2.endNanos()));
        }
        if (obs.a2 != null && obs.a2.sentNanos != 0) {
            wins.add(winOf(raw, "P3", obs.a2.sentNanos, obs.a2.endNanos));
        }
        for (int i = 0; i < obs.plain.size(); i++) {
            wins.add(winOf(raw, "P4#" + (i + 1), obs.plain.get(i).startNanos(), obs.plain.get(i).endNanos()));
        }
        if (obs.a3 != null && obs.a3.sentNanos != 0) {
            wins.add(winOf(raw, "P6a", obs.a3.sentNanos, obs.a3.endNanos));
        }
        if (obs.p6 != null) {
            wins.add(winOf(raw, "P6b", obs.p6.startNanos(), obs.p6.endNanos()));
        }
        return wins;
    }

    /**
     * 전사 조각을 {@code start_ms}로 단계에 귀속한다: 단계 창 안의 출력 음성이 오디오 시계에서 차지한 구간에 start_ms가 들면 그 단계의 것이다(전사가
     * 늦게 도착해도 같은 단계). 어느 구간에도 들지 않으면 "?"(귀속 못 함). 도착 순서를 지킨다.
     */
    static Map<String, List<TextAt>> byStart(List<TextAt> pieces, List<Win> wins) {
        Map<String, List<TextAt>> map = new LinkedHashMap<>();
        wins.forEach(win -> map.put(win.name(), new ArrayList<>()));
        map.put("?", new ArrayList<>());
        for (TextAt piece : pieces) {
            String owner = "?";
            if (piece.startMs() >= 0) {
                for (Win win : wins) {
                    if (win.hasAudio() && piece.startMs() >= win.clockFirstMs() && piece.startMs() < win.clockLastMs()) {
                        owner = win.name();
                        break;
                    }
                }
            }
            map.get(owner).add(piece);
        }
        return map;
    }

    private static RawFrame ackFor(List<RawFrame> raw, String eventId) {
        return raw.stream().filter(frame -> frame.type().contains(APPENDED_MARK) && !frame.type().equals("session.instructions.append")
                && eventId.equals(frame.clientEventId())).findFirst().orElse(null);
    }

    private static String markerBrief(MarkerHit hit) {
        if (hit == null) {
            return "확인 못 함";
        }
        return hit.found() ? "예(표식 앞 전사 " + hit.charsBefore() + "자" + (hit.charsBefore() == 0 ? " · 표식으로 시작" : "") + ")"
                : "아니오(전사 " + hit.windowChars() + "자)";
    }

    private static MarkerHit arrivalHit(Obs obs, String marker, long fromNanos, long toNanos) {
        return findMarker(obs.pieces, marker, piece -> piece.atNanos() >= fromNanos && piece.atNanos() <= toNanos);
    }

    private static int firstIndex(List<Chunk> chunks, java.util.function.Predicate<Chunk> match) {
        for (int i = 0; i < chunks.size(); i++) {
            if (match.test(chunks.get(i))) {
                return i;
            }
        }
        return chunks.size();
    }

    /** 단계별 전사 도착 지연: 전사 end_ms에서 그 조각이 도착한 때의 오디오 시계를 뺀 값(양수면 전사가 음성보다 앞서 도착). */
    static String lagText(Obs obs, List<Win> wins, Map<String, List<TextAt>> attributed) {
        List<String> parts = new ArrayList<>();
        for (Win win : wins) {
            List<Long> lags = obs.pieces.stream().filter(p -> p.endMs() >= 0 && p.atNanos() >= win.startNanos() && p.atNanos() <= win.endNanos())
                    .map(p -> p.endMs() - p.clockMs()).sorted().toList();
            parts.add(win.name() + (lags.isEmpty() ? " 전사 없음" : " n=" + lags.size() + " 최소 " + lags.getFirst() + " 중앙 "
                    + lags.get(lags.size() / 2) + " 최대 " + lags.getLast()));
        }
        long total = obs.pieces.size();
        long unattributed = attributed.getOrDefault("?", List.of()).size();
        boolean monotonic = true;
        long previous = Long.MIN_VALUE;
        for (TextAt piece : obs.pieces) {
            if (piece.startMs() >= 0) {
                monotonic &= piece.startMs() >= previous;
                previous = piece.startMs();
            }
        }
        return String.join(" · ", parts) + " (ms, 전사 end_ms − 도착 때 오디오 시계, 양수 = 전사가 음성보다 앞) · 전사 조각 " + total
                + "개 중 start_ms가 오디오 시계 구간에 든 것 " + (total - unattributed) + "개 · start_ms 도착 순서 단조 " + (monotonic ? "예" : "아니오");
    }

    /** 보고서 행 열아홉 개({@link #ITEMS} 순서). 판정 · 관측만 담고 전사 · 음성 · 키는 담지 않는다. */
    static List<Row> buildRows(Obs obs) {
        List<RawFrame> raw = obs.raw;
        List<Row> rows = new ArrayList<>();
        List<ErrorInfo> errors = raw.stream().map(RawFrame::error).filter(java.util.Objects::nonNull).toList();
        boolean started = raw.stream().anyMatch(frame -> frame.type().equals("session.started"));
        long audioChunks = raw.stream().filter(frame -> OUTPUT_AUDIO_TYPES.contains(frame.type())).count();
        long audioBytes = raw.stream().filter(frame -> OUTPUT_AUDIO_TYPES.contains(frame.type())).mapToLong(RawFrame::audioBytes).sum();
        long unsupportedTypeErrors = errors.stream().filter(error -> "type".equals(error.param())).count();
        long firstAppend = obs.firstAppendNanos();
        List<ErrorInfo> beforeAppend = raw.stream().filter(frame -> frame.error() != null && (firstAppend == 0 || frame.atNanos() < firstAppend))
                .map(RawFrame::error).toList();
        List<ErrorInfo> afterAppend = firstAppend == 0 ? List.of()
                : raw.stream().filter(frame -> frame.error() != null && frame.atNanos() >= firstAppend).map(RawFrame::error).toList();
        int totalFrames = obs.utterances.stream().mapToInt(Utterance::totalFrames).sum();
        int sentFrames = obs.utterances.stream().mapToInt(Utterance::sentFrames).sum();
        boolean sendsOk = !obs.utterances.isEmpty() && obs.utterances.stream().allMatch(u -> u.sendFailure() == null);
        List<Win> wins = windows(obs);
        Map<String, List<TextAt>> attributed = byStart(obs.pieces, wins);

        // ① 표본율 수락
        boolean one = started && obs.startFailure == null && sendsOk && audioBytes > 0 && beforeAppend.isEmpty();
        StringBuilder first = new StringBuilder()
                .append("입력 ").append(obs.rate).append("Hz · 연결 ").append(obs.connectedMillis).append("ms · session.started ")
                .append(started ? obs.startedMillis + "ms" : "없음")
                .append(obs.startFailure == null ? "" : "(start 송신 실패 " + obs.startFailure + ")")
                .append(" · 발화 ").append(obs.utterances.size()).append("개 프레임 ").append(sentFrames).append('/').append(totalFrames)
                .append(" 송신").append(sendsOk ? "" : "(실패 " + obs.utterances.stream().map(Utterance::sendFailure)
                        .filter(java.util.Objects::nonNull).findFirst().orElse("없음") + ")")
                .append(" · 출력 음성 조각 ").append(audioChunks).append("개(").append(audioBytes).append("바이트)")
                .append(" · 지시 추가 전 오류 ").append(beforeAppend.size()).append("건");
        if (obs.input != null && !obs.input.fromEnv()) {
            first.append(" — env ").append(INPUT_PCM_ENV).append(" 미지정이라 저장소 자리 파일(합성 무음 · 톤)을 썼다. 합성 말소리 PCM이 있으면 그")
                    .append(" 경로를 env ").append(INPUT_PCM_ENV).append("(원시면 env ").append(INPUT_RATE_ENV).append("=표본율)로 주고 다시 돌린다");
        }
        rows.add(new Row(ITEMS.get(0), first.toString(), one ? Verdict.PASS : Verdict.FAIL));

        // ② 사건 이름
        List<RawFrame> outputTranscripts = framesOfType(raw, GptLiveProbe::isOutputTranscript);
        List<RawFrame> inputTranscripts = framesOfType(raw, GptLiveProbe::isInputTranscript);
        List<RawFrame> speechEnds = framesOfType(raw, GptLiveProbe::isSpeechEnd);
        List<RawFrame> delegations = framesOfType(raw, type -> type.contains("delegation"));
        boolean inputDone = inputTranscripts.stream().anyMatch(frame -> frame.type().endsWith(".done") || frame.type().endsWith(".completed"));
        rows.add(new Row(ITEMS.get(1), "출력 전사 " + (outputTranscripts.isEmpty() ? "0건(구현을 멈추고 사람과 다시 정한다, BR9.2)"
                : outputTranscripts.size() + "건 " + typeSummary(outputTranscripts))
                + " · 입력 전사 " + (inputTranscripts.isEmpty() ? "0건" : inputTranscripts.size() + "건 " + typeSummary(inputTranscripts))
                + " · 입력 전사 완료 사건 " + (inputDone ? "있음" : "없음")
                + " · 말 끝 사건 " + (speechEnds.isEmpty() ? "없음" : typeSummary(speechEnds))
                + " · 위임 사건 " + (delegations.isEmpty() ? "없음" : typeSummary(delegations))
                + " · 응답 끝 · 경계 후보(모르는 type) " + boundaryCandidates(raw)
                + " · 전사 모양 " + describeShapes(outputTranscripts),
                outputTranscripts.isEmpty() ? Verdict.NOT_OBSERVED : Verdict.PASS));

        // P1 기준선
        Utterance p1 = obs.p1;
        if (p1 == null) {
            rows.add(new Row(ITEMS.get(2), "세션이 시작되지 않아 발화를 보내지 못함", Verdict.NOT_OBSERVED));
        } else {
            List<Long> times = audioTimes(raw, p1.startNanos(), p1.endNanos() + 1);
            if (times.isEmpty()) {
                rows.add(new Row(ITEMS.get(2), "자동 응답 조각 0개(발화 프레임 " + p1.sentFrames() + "/" + p1.totalFrames() + " 송신)",
                        Verdict.NOT_OBSERVED));
            } else {
                rows.add(new Row(ITEMS.get(2), "마지막 입력 소리 프레임 → 첫 출력 조각 " + millisBetween(p1.lastVoicedNanos(), times.getFirst())
                        + "ms · 마지막 송신 프레임(뒤 무음 포함) → 첫 조각 "
                        + millisBetween(p1.lastSentNanos(), times.getFirst()) + "ms (음수면 입력을 다 보내기 전에 응답이 시작됨) · 조각 " + times.size()
                        + "개 · 응답 안 조각 사이 최대 쉼 " + maxGapMillis(times) + "ms · 출력 전사 사건 "
                        + countIn(raw, GptLiveProbe::isOutputTranscript, p1.startNanos(), p1.endNanos() + 1) + "건 · 입력 전사 사건 "
                        + countIn(raw, GptLiveProbe::isInputTranscript, p1.startNanos(), p1.endNanos() + 1) + "건", Verdict.PASS));
            }
        }

        // P2 시간표 귀속 · 경계 규칙
        AppendTrial a1 = obs.a1;
        if (obs.p2 == null || a1 == null) {
            String why = "시험하지 못함(앞 단계 응답이 없었거나 세션이 시작되지 않음)";
            rows.add(new Row(ITEMS.get(3), why, Verdict.NOT_OBSERVED));
            rows.add(new Row(ITEMS.get(4), why, Verdict.NOT_OBSERVED));
        } else if (a1.failure != null) {
            rows.add(new Row(ITEMS.get(3), "지시 #1 송신 실패(" + a1.failure + ")", Verdict.FAIL));
            rows.add(new Row(ITEMS.get(4), "지시 #1을 보내지 못해 비교 불가", Verdict.NOT_OBSERVED));
        } else if (a1.sentNanos == 0) {
            rows.add(new Row(ITEMS.get(3), "발화 뒤 출력 조각이 오지 않아 지시 #1을 보내지 못함", Verdict.NOT_OBSERVED));
            rows.add(new Row(ITEMS.get(4), "지시 #1을 보내지 못해 비교 불가", Verdict.NOT_OBSERVED));
        } else {
            long sent = a1.sentNanos;
            long end = obs.p2.endNanos() + 1;
            RawFrame ack = ackFor(raw, EVENT_ID_P2);
            List<Chunk> chunks = chunksIn(raw, sent, end);
            MarkerHit hit = findMarker(obs.pieces, MARKER_P2, piece -> piece.atNanos() >= sent && piece.atNanos() < end);
            long clockAtSend = raw.stream().filter(frame -> frame.atNanos() <= sent).mapToLong(RawFrame::clockMs).max().orElse(0L);
            StringBuilder text = new StringBuilder("지시 #1은 첫 출력 조각 뒤 " + a1.afterFirstChunkMillis + "ms에 송신(그때 오디오 시계 " + clockAtSend
                    + "ms) · 송신 뒤 조각 " + chunks.size() + "개");
            final long ackEnd = ack == null ? -1L : ack.endMs();
            if (ack == null) {
                text.append(" · 확인 사건 없음");
            } else {
                long ackStart = ack.startMs();
                text.append(" · 확인 사건: 도착 +").append(millisBetween(sent, ack.atNanos())).append("ms · 오디오 시계 ").append(ack.clockMs())
                        .append("ms · start_ms=").append(ackStart).append(" end_ms=").append(ackEnd).append(" · 송신~도착 사이 조각 ")
                        .append(chunks.stream().filter(c -> c.atNanos() < ack.atNanos()).count()).append("개");
            }
            if (hit.found()) {
                text.append(" · 표식 전사: 도착 +").append(millisBetween(sent, hit.foundAtNanos())).append("ms · start_ms=").append(hit.markerStartMs())
                        .append(" · 표식 앞 전사 ").append(hit.charsBefore()).append("자");
                if (ack != null) {
                    text.append(" · 확인 도착~표식 전사 도착 사이 조각 ")
                            .append(chunks.stream().filter(c -> c.atNanos() >= ack.atNanos() && c.atNanos() < hit.foundAtNanos()).count()).append("개");
                }
            } else {
                text.append(" · 표식 응답 시작 아니오(전사 ").append(hit.windowChars()).append("자)");
            }
            int idxAck = ack == null ? chunks.size() : firstIndex(chunks, c -> c.atNanos() >= ack.atNanos());
            int idxEnd = ackEnd < 0 ? chunks.size() : firstIndex(chunks, c -> c.clockStartMs() >= ackEnd);
            int idxTruth = hit.found() && hit.markerStartMs() >= 0 ? firstIndex(chunks, c -> c.clockStartMs() >= hit.markerStartMs()) : chunks.size();
            text.append(" · 조각 위치(0부터, 전체 ").append(chunks.size()).append("): 확인 도착 뒤 첫 조각 ").append(idxAck)
                    .append(" · 시계 ≥ 확인 end_ms 첫 조각 ").append(idxEnd).append(" · 시계 ≥ 표식 start_ms 첫 조각 ").append(idxTruth)
                    .append(" · 송신 뒤 조각 사이 최대 쉼 ").append(maxGapMillis(chunks.stream().map(Chunk::atNanos).toList())).append("ms")
                    .append(" · 경계를 알리는 모르는 type ").append(unknownTypesIn(raw, sent, end));
            rows.add(new Row(ITEMS.get(3), text.toString(), Verdict.INFO));
            if (ack == null || !hit.found() || hit.markerStartMs() < 0 || ackEnd < 0) {
                rows.add(new Row(ITEMS.get(4), "비교하지 못함 — " + (ack == null ? "확인 사건 없음" : !hit.found() ? "표식 응답이 없어 기준을 못 잡음"
                        : "start_ms / end_ms 값이 없음"), Verdict.NOT_OBSERVED));
            } else {
                long cut = hit.markerStartMs();
                long staleTruth = chunks.stream().filter(c -> c.clockStartMs() < cut).count();
                long r1Leak = chunks.stream().filter(c -> c.clockStartMs() < cut && c.atNanos() >= ack.atNanos()).count();
                long r1Drop = chunks.stream().filter(c -> c.clockStartMs() >= cut && c.atNanos() < ack.atNanos()).count();
                long endCut = ackEnd;
                long r2Leak = chunks.stream().filter(c -> c.clockStartMs() < cut && c.clockStartMs() >= endCut).count();
                long r2Drop = chunks.stream().filter(c -> c.clockStartMs() >= cut && c.clockStartMs() < endCut).count();
                rows.add(new Row(ITEMS.get(4), "기준(시계 ≥ 표식 start_ms " + cut + "이면 새 응답): 낡은 조각 " + staleTruth + "개 · 새 조각 "
                        + (chunks.size() - staleTruth) + "개 | R1(확인 도착 뒤 조각 = 새 응답): 낡은 조각 통과 " + r1Leak + "개 · 새 조각 잘못 버림 "
                        + r1Drop + "개 | R2(시계 ≥ 확인 end_ms " + endCut + " = 새 응답): 낡은 조각 통과 " + r2Leak + "개 · 새 조각 잘못 버림 "
                        + r2Drop + "개 | 확인 end_ms − 표식 start_ms = " + (endCut - cut) + "ms · 확인 도착 때 시계 − 표식 start_ms = "
                        + (ack.clockMs() - cut) + "ms (start_ms가 오디오 시계와 같은 시간축이라는 가정 위의 계산)", Verdict.INFO));
            }
        }

        // P3 유휴 추가(조용해진 뒤 창)
        AppendTrial a2 = obs.a2;
        if (a2 == null) {
            rows.add(new Row(ITEMS.get(5), "시험하지 못함(세션이 시작되지 않음)", Verdict.NOT_OBSERVED));
        } else if (a2.failure != null) {
            rows.add(new Row(ITEMS.get(5), "지시 #2 송신 실패(" + a2.failure + ")", Verdict.FAIL));
        } else {
            long endNanos = a2.endNanos;
            List<Chunk> chunks = chunksIn(raw, a2.sentNanos, endNanos + 1);
            MarkerHit byArrival = arrivalHit(obs, MARKER_P3, a2.sentNanos, endNanos);
            MarkerHit byStart = findMarker(attributed.getOrDefault("P3", List.of()), MARKER_P3, piece -> true);
            long late = attributed.getOrDefault("P3", List.of()).stream().filter(piece -> piece.atNanos() > endNanos).count();
            rows.add(new Row(ITEMS.get(5), "송신 직전 조용했던 시간 " + (a2.idleMillis < 0 ? "모름" : a2.idleMillis + "ms") + " · 말이 저절로 시작 "
                    + (chunks.isEmpty() ? "아니오(조각 0개)" : "예(첫 조각 +" + millisBetween(a2.sentNanos, chunks.getFirst().atNanos()) + "ms · 조각 "
                            + chunks.size() + "개 · 음성 길이 " + (chunks.getLast().clockEndMs() - chunks.getFirst().clockStartMs()) + "ms)")
                    + " · P3 표식 start_ms 귀속 " + markerBrief(byStart) + " / 도착 창 " + markerBrief(byArrival)
                    + " · 전사 글자 수 start_ms 귀속 " + byStart.windowChars() + " / 도착 창 " + byArrival.windowChars()
                    + " · 창이 닫힌 뒤 늦게 도착한 P3 전사 조각 " + late + "개 · 모르는 type " + unknownTypesIn(raw, a2.sentNanos, endNanos + 1),
                    Verdict.INFO));
        }

        // P4 지속성(start_ms 귀속)
        if (obs.plain.isEmpty()) {
            rows.add(new Row(ITEMS.get(6), "시험하지 못함(세션이 시작되지 않았거나 앞에서 끊김)", Verdict.NOT_OBSERVED));
        } else {
            MarkerHit p3Hit = findMarker(attributed.getOrDefault("P3", List.of()), MARKER_P3, piece -> true);
            List<String> parts = new ArrayList<>();
            List<String> verdicts = new ArrayList<>();
            boolean anyAudio = false;
            for (int i = 0; i < obs.plain.size(); i++) {
                String name = "P4#" + (i + 1);
                Utterance plain = obs.plain.get(i);
                int chunkCount = chunksIn(raw, plain.startNanos(), plain.endNanos() + 1).size();
                anyAudio |= chunkCount > 0;
                MarkerHit hit3 = findMarker(attributed.getOrDefault(name, List.of()), MARKER_P3, piece -> true);
                MarkerHit hit3Arrival = arrivalHit(obs, MARKER_P3, plain.startNanos(), plain.endNanos());
                MarkerHit hit2 = findMarker(attributed.getOrDefault(name, List.of()), MARKER_P2, piece -> true);
                parts.add("평범한 발화 " + (i + 1) + "회 뒤: 조각 " + chunkCount + "개 · P3 표식 start_ms 귀속 " + markerBrief(hit3) + " / 도착 창 "
                        + markerBrief(hit3Arrival) + " · P2 표식 " + markerBrief(hit2));
                verdicts.add(chunkCount == 0 ? "응답 없음" : hit3.found() ? "예" : "아니오");
            }
            String conclusion = !p3Hit.found() ? "P3에서 표식을 못 봐서 유지 여부는 판단 불가"
                    : "P3 표식 유지: " + String.join(" / ", java.util.stream.IntStream.range(0, verdicts.size())
                            .mapToObj(i -> (i + 1) + "회 뒤 " + verdicts.get(i)).toList());
            rows.add(new Row(ITEMS.get(6), String.join(" | ", parts) + " → " + conclusion, anyAudio ? Verdict.INFO : Verdict.NOT_OBSERVED));
        }

        // P6 한 번뿐 지시
        if (!obs.oneShotPlanned) {
            rows.add(new Row(ITEMS.get(7), "시험 안 함(env " + ONE_SHOT_ENV + "로 껐다)", Verdict.INFO));
        } else if (obs.a3 == null || obs.a3.sentNanos == 0 || obs.a3.failure != null) {
            rows.add(new Row(ITEMS.get(7), obs.a3 != null && obs.a3.failure != null ? "지시 송신 실패(" + obs.a3.failure + ")"
                    : "시험하지 못함(앞에서 끊김)", obs.a3 != null && obs.a3.failure != null ? Verdict.FAIL : Verdict.NOT_OBSERVED));
        } else {
            AppendTrial a3 = obs.a3;
            List<Chunk> speech = chunksIn(raw, a3.sentNanos, a3.endNanos + 1);
            MarkerHit self = findMarker(attributed.getOrDefault("P6a", List.of()), MARKER_P6, piece -> true);
            MarkerHit selfArrival = arrivalHit(obs, MARKER_P6, a3.sentNanos, a3.endNanos);
            MarkerHit nextHit = findMarker(attributed.getOrDefault("P6b", List.of()), MARKER_P6, piece -> true);
            MarkerHit nextArrival = obs.p6 == null ? null : arrivalHit(obs, MARKER_P6, obs.p6.startNanos(), obs.p6.endNanos());
            MarkerHit nextP3 = findMarker(attributed.getOrDefault("P6b", List.of()), MARKER_P3, piece -> true);
            int nextChunks = obs.p6 == null ? 0 : chunksIn(raw, obs.p6.startNanos(), obs.p6.endNanos() + 1).size();
            String conclusion = !self.found() ? "지시 직후 표식 말을 못 봐서 한 번뿐인지 판단 불가"
                    : nextChunks == 0 ? "다음 평범한 발화에 응답이 없어 판단 불가"
                    : nextHit.found() ? "한 번뿐이라 했는데 다음 평범한 발화에도 표식이 남았다" : "한 번뿐 지시가 지켜졌다(다음 평범한 발화에 표식 없음)";
            rows.add(new Row(ITEMS.get(7), "지시 직후 말이 저절로 시작 " + (speech.isEmpty() ? "아니오(조각 0개)" : "예(조각 " + speech.size() + "개)")
                    + " · P6 표식 start_ms 귀속 " + markerBrief(self) + " / 도착 창 " + markerBrief(selfArrival)
                    + " · 다음 평범한 발화: 조각 " + nextChunks + "개 · P6 표식 start_ms 귀속 " + markerBrief(nextHit) + " / 도착 창 "
                    + markerBrief(nextArrival) + " · 그 발화의 P3 표식 " + markerBrief(nextP3) + " → " + conclusion, Verdict.INFO));
        }

        // 전사 도착 지연
        rows.add(new Row(ITEMS.get(8), obs.pieces.isEmpty() ? "출력 전사 조각이 없다" : lagText(obs, wins, attributed),
                obs.pieces.isEmpty() ? Verdict.NOT_OBSERVED : Verdict.INFO));

        // 지시 추가 수락
        List<RawFrame> acks = framesOfType(raw, type -> type.contains(APPENDED_MARK) && !type.equals("session.instructions.append"));
        List<String> sentIds = obs.sentEventIds();
        long matched = acks.stream().filter(frame -> frame.clientEventId() != null && sentIds.contains(frame.clientEventId())).count();
        List<Integer> lengths = raw.stream().filter(frame -> frame.instructionsLength() >= 0).map(RawFrame::instructionsLength).toList();
        String failure = java.util.stream.Stream.of(obs.a1, obs.a2, obs.a3).filter(java.util.Objects::nonNull)
                .map(trial -> trial.failure).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        boolean accepted = !sentIds.isEmpty() && failure == null && afterAppend.isEmpty();
        rows.add(new Row(ITEMS.get(ACCEPT_ROW), "지시 추가 " + sentIds.size() + "번 송신" + (failure == null ? "" : "(송신 실패 " + failure + ")")
                + " · 보낸 뒤 오류 " + afterAppend.size() + "건" + (afterAppend.isEmpty() ? "" : " " + describeErrors(afterAppend))
                + " · 확인 사건 " + acks.size() + "건" + (acks.isEmpty() ? "" : " " + typeSummary(acks))
                + " · client_event_id가 보낸 event_id와 일치 " + matched + "/" + sentIds.size()
                + " · 확인 사건 모양 " + describeShapes(acks)
                + " · 지시문 길이를 싣는 사건 " + (lengths.isEmpty() ? "없음(누적 여부는 표식으로만 본다)" : lengths + " (늘어나면 누적, 그대로면 교체)"),
                accepted ? Verdict.PASS : sentIds.isEmpty() && failure == null ? Verdict.NOT_OBSERVED : Verdict.FAIL));

        // response.create 미사용
        rows.add(new Row(ITEMS.get(10), "response.create 송신 0건(이 도구는 보내지 않는다 — 클라이언트 위임 세션은 거절한다고 앞 실행에서 관측됨) · 지원 목록 밖 type 오류 "
                + unsupportedTypeErrors + "건", unsupportedTypeErrors == 0 ? Verdict.PASS : Verdict.FAIL));

        // 종료 형식
        rows.add(new Row(ITEMS.get(11), obs.closedEarly
                ? "확인 전에 연결이 먼저 닫힘(아래 '연결 끊김' 참고)"
                : "session.close 송신" + (obs.closeFailure == null ? "" : " 실패(" + obs.closeFailure + ")") + " → session.closed 메시지 "
                        + (obs.closedMessage ? "있음" : "없음") + " · " + (obs.closeMillis >= 0 && obs.closedMessage
                                ? obs.closeMillis + "ms에 닫힘" : "닫힘 확인 못 함"),
                !obs.closedEarly && obs.closeFailure == null && obs.closedMessage ? Verdict.PASS : Verdict.NOT_OBSERVED));

        // 오류 사건
        rows.add(new Row(ITEMS.get(12), errors.isEmpty() ? "없음" : errors.size() + "건 — " + describeErrors(errors),
                errors.isEmpty() ? Verdict.INFO : Verdict.FAIL));

        // 지원 type 목록(오류 문장에서, 200자 자르기 · 가림 전 원문 기준)
        rows.add(new Row(ITEMS.get(13), describeTypeLists(errors), Verdict.INFO));

        // 수신 사건 모양
        rows.add(new Row(ITEMS.get(14), describeShapes(raw), Verdict.INFO));

        // 연결 끊김 · 마지막 사건
        rows.add(new Row(ITEMS.get(15), describeClose(obs.closeCode, obs.socketError, obs.lastTypeBeforeClose, obs.closedEarly,
                obs.closedMessage), Verdict.INFO));

        // 앱 해석기 이해
        rows.add(new Row(ITEMS.get(16), "session.started " + obs.interpretedStarted + "건 · 출력 음성 " + obs.interpretedAudio + "조각("
                + obs.interpretedAudioBytes + "바이트) · session.usage.updated " + obs.interpretedUsage + "건 · error "
                + obs.interpretedErrors + "건 (원시 출력 음성 " + audioBytes + "바이트)", Verdict.INFO));

        // 앱이 모르는 type
        Map<String, Integer> unknown = new TreeMap<>();
        raw.stream().filter(frame -> !KNOWN_TYPES.contains(frame.type()) && !frame.type().equals(CLOSED_MARK))
                .forEach(frame -> unknown.merge(frame.type(), 1, Integer::sum));
        rows.add(new Row(ITEMS.get(17), unknown.isEmpty() ? "없음" : unknown.toString(), Verdict.INFO));

        // 사용량
        rows.add(new Row(ITEMS.get(18), describeUsage(raw, obs.sessionMillis), Verdict.INFO));
        return rows;
    }

    /** 사용량 행: session.usage.updated 건수 · 마지막 값, session.closed에 실린 값, 세션 길이(금액 계산 없음). */
    static String describeUsage(List<RawFrame> raw, Long sessionMillis) {
        List<RawFrame> updates = raw.stream().filter(frame -> frame.type().equals(USAGE_TYPE)).toList();
        String last = updates.stream().map(RawFrame::usage).filter(java.util.Objects::nonNull).reduce((first, second) -> second)
                .orElse(null);
        String closed = raw.stream().filter(frame -> frame.type().equals("session.closed")).map(RawFrame::usage)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        return USAGE_TYPE + " " + updates.size() + "건 · 마지막 " + (last == null ? "없음" : last) + " · session.closed 사용량 "
                + (closed == null ? "없음" : closed) + " · 세션 길이(started ~ 닫힘) " + (sessionMillis == null ? "모름" : sessionMillis + "ms");
    }

    /** 오류 사건들의 지원 type 목록(합쳐 중복 제거 · 정렬)과 거절된 값. */
    static String describeTypeLists(List<ErrorInfo> errors) {
        Set<String> supported = new TreeSet<>();
        Set<String> rejected = new TreeSet<>();
        for (ErrorInfo error : errors) {
            supported.addAll(error.supportedTypes());
            rejected.addAll(error.rejectedTypes());
        }
        if (supported.isEmpty() && rejected.isEmpty()) {
            return "없음 — 오류 문장에 type 이름 목록이 없었다";
        }
        return "지원 " + supported.size() + "개 " + supported + " · 거절된 값 " + (rejected.isEmpty() ? "없음" : rejected.toString());
    }

    /** type별 첫 사건의 모양(키 · 값 타입만). */
    static String describeShapes(List<RawFrame> raw) {
        Map<String, String> shapes = new LinkedHashMap<>();
        for (RawFrame frame : raw) {
            if (!frame.type().equals(CLOSED_MARK) && frame.shape() != null && !"-".equals(frame.shape())) {
                shapes.putIfAbsent(frame.type(), frame.shape());
            }
        }
        if (shapes.isEmpty()) {
            return "없음";
        }
        List<String> parts = new ArrayList<>();
        shapes.forEach((type, shape) -> parts.add(type + " " + shape));
        return String.join(" ; ", parts);
    }

    static String describeErrors(List<ErrorInfo> errors) {
        List<String> parts = errors.stream().limit(5).map(ErrorInfo::describe).toList();
        return String.join(" ; ", parts) + (errors.size() > 5 ? " ; 외 " + (errors.size() - 5) + "건" : "");
    }

    static String describeClose(Integer code, String error, String last, boolean closedEarly, boolean closedMessage) {
        String how;
        if (code != null) {
            how = "닫힘 코드 " + code;
        } else if (error != null) {
            how = "close 프레임 없이 끊김(1006 해당, " + error + ")";
        } else {
            how = "소켓 닫힘 신호 없음(정리 단계에서 이 도구가 닫음)";
        }
        return (closedEarly ? "확인 전에 공급자 쪽에서 끊김 · " : "") + how + " · session.closed 메시지 " + (closedMessage ? "있음" : "없음")
                + " · 끊기기 전 마지막 사건 " + (last == null ? "해당 없음" : last);
    }

    private static long millisSince(long startNanos) {
        return millisBetween(startNanos, System.nanoTime());
    }

    private static long millisBetween(long startNanos, long endNanos) {
        return TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos);
    }

    /** 예외 종류 이름만(메시지에는 주소 · 본문이 들 수 있어 쓰지 않는다). */
    static String failureName(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause instanceof ExecutionException) {
            cause = cause.getCause();
        }
        if (cause instanceof ProviderPort.ProviderUnavailableException unavailable) {
            return unavailable.reason().name();
        }
        return cause.getClass().getSimpleName();
    }

    // ---- 보고서 ----

    private void writeReport(Result result, InputAudio input) throws IOException {
        String text = renderReport(result, input, new SystemMujoongClock().now().toString());
        if (report.getParent() != null) {
            Files.createDirectories(report.getParent());
        }
        Files.writeString(report, text, StandardCharsets.UTF_8);
    }

    /** 보고서 본문(맨 위 outcome은 ① 입력 표본율 수락과 지시 추가 수락 기준, 표는 항목 · 판정 · 관측). */
    static String renderReport(Result result, InputAudio input, String nowUtc) {
        boolean skipped = result.outcome() == Outcome.SKIPPED_NO_KEY;
        StringBuilder out = new StringBuilder();
        out.append("# GPT-Live 격리 실호출 보고서 (U11 W7 · FR9.1 · BR9.2)\n\n");
        out.append("outcome=").append(result.outcome()).append(" evidence=").append(skipped ? "NONE" : "REAL_API_SYNTHETIC").append('\n');
        out.append("rate=").append(result.rate()).append(" phases=").append(phasesOf(result, skipped)).append("\n\n");
        out.append("- ").append(PRICE_NOTE).append('\n');
        out.append("- 실행 시각(UTC): ").append(nowUtc).append('\n');
        if (skipped) {
            out.append("- 증거 종류: 없음(실호출 안 함). SKIPPED_NO_KEY는 통과로 보지 않는다(BR9.1).\n");
        } else {
            out.append("- 증거 종류: [실호출-합성] 실제 API · 합성 음성(R-M11). 테스트 대역 · 실제 기기 증거가 아니다.\n");
            out.append("- 모델(코드 상수): ").append(ProviderRequestFactory.MODEL).append('\n');
        }
        if (input != null) {
            out.append("- 입력 음성: ").append(input.sourceLabel()).append(" · ").append(input.millis()).append("ms (")
                    .append(result.rate()).append("Hz로 맞춰 보냄, 발화마다 뒤 무음 ").append(TRAILING_SILENCE_FRAMES).append("프레임)\n");
            out.append("- 표식 낱말: P2 ").append(MARKER_P2).append(" · P3 ").append(MARKER_P3)
                    .append(" (지시문이 응답의 첫 낱말을 표식으로 시작하라고 시킨다. 도구가 출력 전사에 표식이 있는지를 메모리에서만 확인해 예 · 아니오와 시간만 남긴다)\n");
            out.append("- response.create는 보내지 않는다. 지시 추가는 원시 JSON(event_id · delegation_id: null · content)이다. 단계 사이에는 응답이 끝난 뒤")
                    .append(" 2초 이상 조용한 틈을 둔다.\n");
        }
        out.append("- outcome은 ① 입력 표본율 수락과 '지시 추가 수락'(지시를 보낸 뒤 오류 0건)이 모두 PASS일 때만 PASS다. P1 ~ P5는 계속 · 중단을")
                .append(" 사람이 정하는 관측이다(BR9.2: ② 출력 전사 사건이 NOT_OBSERVED면 구현을 멈추고 사람과 다시 정한다).\n");
        out.append("- 이 보고서에는 전사 원문 · 음성 · 키 · 주소를 담지 않는다. 오류 문장은 앞 ").append(ERROR_MESSAGE_MAX)
                .append("자까지 가려서 남긴다(R-F3 · R-F14).\n\n");
        out.append("| 항목 | 판정 · 관측 |\n|---|---|\n");
        out.append("| ").append(cell(result.hostRule().item())).append(" | ").append(cellOf(result.hostRule())).append(" |\n");
        for (Row row : result.rows()) {
            out.append("| ").append(cell(row.item())).append(" | ").append(cellOf(row)).append(" |\n");
        }
        return out.toString();
    }

    private static String cellOf(Row row) {
        return "**" + row.verdict() + "** · " + cell(row.observation());
    }

    private static String cell(String value) {
        return value.replace("|", "/").replace("\n", " ");
    }

    /** 보고서 머리의 실행 단계 표시: 건너뛴 보고서는 '-', 아니면 P1 ~ P4(+ 시험했으면 P6). */
    private static String phasesOf(Result result, boolean skipped) {
        if (skipped) {
            return "-";
        }
        boolean p6 = result.rows().size() > 7 && !result.rows().get(7).observation().startsWith("시험 안 함");
        return p6 ? "P1,P2,P3,P4,P6" : "P1,P2,P3,P4";
    }

    /** 사람이 읽는 한 줄 요약(원문 없음). */
    static String summary(Result result) {
        return "GPT-Live 실호출 " + result.outcome() + " — "
                + String.join(" ", result.rows().stream().limit(9).map(row -> row.item().split(" ")[0] + "=" + row.verdict()).toList());
    }

    static boolean anyVerdict(Result result, Verdict verdict) {
        return result.hostRule().verdict() == verdict || result.rows().stream().anyMatch(row -> row.verdict() == verdict);
    }
}
