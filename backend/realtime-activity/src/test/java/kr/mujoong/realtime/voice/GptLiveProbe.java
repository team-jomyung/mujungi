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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * GPT-Live 격리 실호출 확인 도구 (U11 FR9.1 · FR9.2 · BR9.1 · BR9.2 · 기능 설계 W7, U9-c와 같은 방식).
 *
 * <p>사람이 env {@code PROVIDER_API_KEY}를 넣고 {@code ./gradlew :realtime-activity:gptLiveTest}로만 부른다. 기본 test · check · CI ·
 * verify.sh에서는 빠진다(build-logic 고정 제외 + 루트 {@code verifyGptLiveIsolation}). 키가 없으면 연결하지 않고
 * {@code SKIPPED_NO_KEY}를 보고하며 이것은 통과가 아니다.
 *
 * <p>한 실행에서 두 번 시도한다.
 * <ul>
 *   <li><b>앱 형식</b>: 운영 송수신 부품 그대로 — 요청은 {@link ProviderRequestFactory}, 송신은 {@link ProviderClient.Session}, 해석은
 *       {@link ProviderClient.Reader}. 입력 24kHz. 맨 위 outcome과 계속 · 중단 기준(BR9.2)은 이 시도의 ① · ②다.
 *   <li><b>키트 형식</b>(비교용): 사람 PC의 독립 키트가 쓰는 {@code session.start{event_id, session{model, instructions, audio{format{type:
 *       audio/pcm, rate: 16000}}, delegation{type: client}}}} 모양, 입력 16kHz. 지시문은 합성 고정 문장이고 아이 정보는 없다. 지시 교정 ·
 *       응답 생성 · 응답 취소는 운영 팩토리 레코드를 그대로 보내 통하는지 본다. 운영 선 형식은 바꾸지 않는다(U11 범위 밖).
 * </ul>
 *
 * <p>두 형식을 같은 기준으로 재려고 판정은 받은 원시 메시지(type 이름 · 응답 id · 음성 바이트 수 · 오류 필드)로 한다. 앱 형식 ①은 운영 해석기가
 * 출력 음성을 알아들었는지도 함께 본다. 연결 열기는 {@link ProviderClient#open}과 같은 방식(Bearer 헤더 · JVM 기본 프록시)이고, 주소는 demo
 * 프로필 파일의 {@code mujoong.provider.url}이며 {@link ProviderHosts} demo 규칙을 통과해야만 연결한다(규칙 우회 없음).
 *
 * <p>입력은 합성 음성만: env {@code GPT_LIVE_INPUT_PCM}(WAV 또는 PCM16LE 모노 원시 파일, 원시면 표본율 env {@code GPT_LIVE_INPUT_RATE},
 * 기본 24000), 없으면 저장소 합성음 자리 파일({@value #DEFAULT_INPUT}). 표본율 변환은 이 도구의 선형 보간(새 의존성 없음).
 *
 * <p>보고서에는 항목 · 관측(개수 · 시간 · type 이름 · 키 이름 · 가린 오류 문장) · 판정만 쓴다. 전사 원문 · 음성 · 키 · 주소는 쓰지 않는다
 * (R-F3 · R-F14).
 */
final class GptLiveProbe {

    /** 사람이 주는 합성 입력 음성 파일 경로 env 이름(비밀 아님). */
    static final String INPUT_PCM_ENV = "GPT_LIVE_INPUT_PCM";
    /** 원시 PCM 입력 파일의 표본율 env 이름(비밀 아님, 기본 24000). */
    static final String INPUT_RATE_ENV = "GPT_LIVE_INPUT_RATE";
    /** 시도할 형식 env 이름(비밀 아님, 기본 {@code app,kit}, {@code kit}만 주면 키트 형식 한 번만). */
    static final String FORMATS_ENV = "GPT_LIVE_FORMATS";
    /** 보고서 머리 문장(금액을 계산하지 않는다). */
    static final String PRICE_NOTE = "단가는 OpenAI 결제 화면에서 확인한다(이 보고서는 사용량만 남긴다).";
    /** 보고서 경로 시스템 속성(gptLiveTest 작업이 build/gpt-live/report.md로 넣는다). */
    static final String REPORT_PROPERTY = "mujoong.gpt.live.report";
    /** 기본 입력: 저장소의 합성음 자리 파일(저장소 루트 기준). */
    static final String DEFAULT_INPUT = "infra/seed-audio/intro-free-talk-v2.wav";
    /** 공급자 주소가 있는 프로필 파일(실제 주소 기본값은 demo · prod 파일에만 있다, ai-io.md 2절). */
    static final String DEMO_PROFILE_RESOURCE = "application-demo.yml";
    static final String PROVIDER_URL_KEY = "mujoong.provider.url";

    /** 앱 형식 표본율(J27). */
    static final int APP_RATE = 24_000;
    /** 키트 형식 표본율. */
    static final int KIT_RATE = 16_000;
    /** 앱 형식 기기 프레임 한 개(20ms) 바이트 수. */
    static final int FRAME_BYTES = AudioFrameCodec.INPUT_PAYLOAD_BYTES;
    /** 키트 형식 20ms 프레임 바이트 수(16kHz · 16비트 · 모노). */
    static final int KIT_FRAME_BYTES = KIT_RATE / 50 * 2;
    /** 말 끝 신호가 나오게 입력 뒤에 붙이는 무음 프레임 수(1.5초). */
    static final int TRAILING_SILENCE_FRAMES = 75;
    /** 키트 형식 지시문(합성 고정 문장, 아이 정보 없음). */
    static final String KIT_INSTRUCTIONS = "합성 시험 세션이다. 들은 말에 짧은 한국어 한두 문장으로 친절하게 대답한다.";
    /** 키트 형식 event_id 접두(식별자 아님). */
    static final String KIT_EVENT_PREFIX = "u11probe";
    /** 오류 문장을 보고서에 남기는 최대 길이. */
    static final int ERROR_MESSAGE_MAX = 200;

    // 실호출 시험 전용 대기 시간(운영 코드가 아니라 TimeLiteralScanTest 대상 밖). 연결 시한은 운영 값 mujoong.provider.connect-timeout과 같다
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration STARTED_WAIT = Duration.ofSeconds(10);
    private static final Duration FRAME_PACE = Duration.ofMillis(20);
    private static final Duration PHASE_A_WAIT = Duration.ofSeconds(20);
    private static final Duration SETTLE_QUIET = Duration.ofSeconds(2);
    private static final Duration NO_OUTPUT_QUIET = Duration.ofSeconds(6);
    private static final Duration PHASE_B_WAIT = Duration.ofSeconds(20);
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);
    private static final Duration POLL = Duration.ofMillis(100);

    /** 앱 해석기({@link ProviderClient#parse})가 아는 type. */
    static final Set<String> KNOWN_TYPES = Set.of("session.started", "session.input_audio.speech_stopped",
            "session.input_transcript.delta", "session.input_transcript.done", "session.output_transcript.delta",
            "session.output_audio.delta", "session.output.done", "session.error", "session.closed");
    private static final Set<String> OUTPUT_DELTA_TYPES = Set.of("session.output_transcript.delta", "session.output_audio.delta");
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

    /** 실행 결과(① · ② 기준, BR9.2). NOT_RUN은 env {@value #FORMATS_ENV}에서 고르지 않은 형식이다. */
    enum Outcome {
        PASS,
        FAIL,
        SKIPPED_NO_KEY,
        NOT_RUN
    }

    /** 항목 하나의 판정. INFO는 통과 · 실패가 아니라 다음 단계 입력이다. */
    enum Verdict {
        PASS,
        FAIL,
        NOT_OBSERVED,
        INFO,
        SKIPPED_NO_KEY
    }

    /** 시도 방식. */
    enum Mode {
        APP("앱 형식"),
        KIT("키트 형식");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /** 보고서 칸 하나. 관측에는 원문을 넣지 않는다. */
    record Row(String item, String observation, Verdict verdict) {
    }

    /** 한 시도의 결과와 행. */
    record Attempt(Mode mode, Outcome outcome, List<Row> rows) {
    }

    /** 실행 결과: 맨 위 outcome은 앱 형식 기준, 키트 형식은 비교용. */
    record Result(Row hostRule, Attempt app, Attempt kit) {

        Outcome outcome() {
            return app.outcome();
        }

        Outcome kitOutcome() {
            return kit.outcome();
        }
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
    private final JsonCodec json = JsonCodec.shared();
    private final ProviderRequestFactory factory = new ProviderRequestFactory();

    GptLiveProbe(Function<String, String> env, Path repoRoot, Path report, Opener opener) {
        this.env = env;
        this.repoRoot = repoRoot;
        this.report = report;
        this.opener = opener;
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
        URI url = providerUrl();
        Optional<ProviderHosts.Problem> problem = ProviderHosts.check(url, "demo");
        if (problem.isPresent()) {
            Row host = new Row("공급자 주소 규칙", "demo 규칙(ProviderHosts) 위반: " + problem.get(), Verdict.FAIL);
            Result blocked = new Result(host, notRun(Mode.APP, "주소 규칙 위반으로 연결 안 함", Outcome.FAIL),
                    notRun(Mode.KIT, "주소 규칙 위반으로 연결 안 함", Outcome.FAIL));
            writeReport(blocked, null);
            return blocked;
        }
        Row host = new Row("공급자 주소 규칙", "demo 프로필 주소가 ProviderHosts demo 규칙(wss · 허용 호스트) 통과", Verdict.PASS);
        Set<Mode> formats = formats(env.apply(FORMATS_ENV));
        InputAudio input = loadInput();
        String notSelected = "실행 안 함(env " + FORMATS_ENV + "에 없음)";
        Attempt app = formats.contains(Mode.APP) ? attempt(Mode.APP, url, key, input) : notRun(Mode.APP, notSelected, Outcome.NOT_RUN);
        Attempt kit = formats.contains(Mode.KIT) ? attempt(Mode.KIT, url, key, input) : notRun(Mode.KIT, notSelected, Outcome.NOT_RUN);
        Result result = new Result(host, app, kit);
        writeReport(result, input);
        return result;
    }

    /** 시도할 형식(env {@value #FORMATS_ENV}, 기본 app,kit). 모르는 값 · 빈 목록이면 거절한다(부르기 전). */
    static Set<Mode> formats(String value) throws IOException {
        if (value == null || value.isBlank()) {
            return EnumSet.of(Mode.APP, Mode.KIT);
        }
        Set<Mode> formats = EnumSet.noneOf(Mode.class);
        for (String part : value.split(",")) {
            String name = part.strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            switch (name) {
                case "app" -> formats.add(Mode.APP);
                case "kit" -> formats.add(Mode.KIT);
                default -> throw new IOException(FORMATS_ENV + "는 app · kit 중에서 쉼표로 고른다");
            }
        }
        if (formats.isEmpty()) {
            throw new IOException(FORMATS_ENV + "가 비었다");
        }
        return formats;
    }

    /** 보고서 행 순서(두 시도가 같은 순서로 채운다). */
    static final List<String> ITEMS = List.of("① 서버 쪽 음성 전송 · 출력 수신", "② instructions.correct + response.create 뒤 응답",
            "③ speech_stopped", "④ 종료 형식", "⑤-1 응답 생성 확인 사건(첫 조각 전 응답 id)", "⑤-2 자동 응답 · 막기 수단",
            "오류 사건(error · session.error)", "지원 type 목록(오류 문장에서)", "수신 사건 모양(type별 첫 사건, 키 · 값 타입)",
            "연결 끊김 · 끊기기 전 마지막 사건", "앱 해석기(ProviderClient.Reader) 이해", "참고: 앱이 모르는 type",
            "사용량(session.usage.updated 마지막 값 · 세션 길이)");

    /** 키가 없을 때의 결과(모든 항목 SKIPPED_NO_KEY, 통과 아님). */
    static Result skipped() {
        Row host = new Row("공급자 주소 규칙", "실호출 안 함(env PROVIDER_API_KEY 없음)", Verdict.SKIPPED_NO_KEY);
        return new Result(host, notRun(Mode.APP, "실호출 안 함(env PROVIDER_API_KEY 없음)", Outcome.SKIPPED_NO_KEY),
                notRun(Mode.KIT, "실호출 안 함(env PROVIDER_API_KEY 없음)", Outcome.SKIPPED_NO_KEY));
    }

    private static Attempt notRun(Mode mode, String why, Outcome outcome) {
        Verdict verdict = switch (outcome) {
            case SKIPPED_NO_KEY -> Verdict.SKIPPED_NO_KEY;
            case NOT_RUN -> Verdict.INFO;
            default -> Verdict.FAIL;
        };
        return new Attempt(mode, outcome, ITEMS.stream().map(item -> new Row(item, why, verdict)).toList());
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
        List<byte[]> frames = new ArrayList<>();
        for (int offset = 0; offset < pcm.length; offset += frameBytes) {
            byte[] frame = new byte[frameBytes];
            System.arraycopy(pcm, offset, frame, 0, Math.min(frameBytes, pcm.length - offset));
            frames.add(frame);
        }
        for (int i = 0; i < TRAILING_SILENCE_FRAMES; i++) {
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

    // ---- 키트 형식 메시지 ----

    /** 키트 형식 session.start(합성 지시문, 아이 정보 없음). */
    String kitSessionStart() {
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "audio/pcm");
        format.put("rate", KIT_RATE);
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("model", ProviderRequestFactory.MODEL);
        session.put("instructions", KIT_INSTRUCTIONS);
        session.put("audio", Map.of("format", format));
        session.put("delegation", Map.of("type", "client"));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", ProviderRequestFactory.TYPE_SESSION_START);
        message.put("event_id", "start_" + KIT_EVENT_PREFIX);
        message.put("session", session);
        return json.write(message);
    }

    /** 키트 형식 입력 음성 한 조각. */
    String kitAudioAppend(byte[] pcm) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", ProviderRequestFactory.TYPE_INPUT_AUDIO_APPEND);
        message.put("audio", Base64.getEncoder().encodeToString(pcm));
        return json.write(message);
    }

    /** 키트 형식 session.close. */
    String kitSessionClose() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", ProviderRequestFactory.TYPE_SESSION_CLOSE);
        message.put("event_id", "close_" + KIT_EVENT_PREFIX);
        return json.write(message);
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
            String usage) {

        RawFrame(int seq, String type, String responseId, List<String> keys) {
            this(seq, type, responseId, keys, 0, null, "-", null);
        }
    }

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
        return new RawFrame(seq, type, responseId, keys, audioBytes, error, shapeOf(fields, 0), usage);
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
        /** 원시 관측(대기 판단용). */
        final LinkedBlockingQueue<RawFrame> rawQueue = new LinkedBlockingQueue<>();
        private final List<RawFrame> raw = new ArrayList<>();
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
            RawFrame frame = parseRaw(json, rawCount(), message);
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
            add(new RawFrame(rawCount(), CLOSED_MARK, null, List.of()));
        }

        /** session.started ~ 닫힘(session.closed 또는 소켓 닫힘) 길이 ms. 둘 중 하나가 없으면 null. */
        Long sessionMillis() {
            return startedNanos == 0 || closedNanos == 0 ? null : TimeUnit.NANOSECONDS.toMillis(closedNanos - startedNanos);
        }

        private void add(RawFrame frame) {
            synchronized (raw) {
                raw.add(frame);
            }
            rawQueue.add(frame);
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

    static String safe(String token) {
        return token != null && SAFE_TOKEN.matcher(token).matches() ? token : "(형식 밖)";
    }

    // ---- 관측 ----

    /** 단계별 원시 관측 개수(원문 없음). */
    static final class Stats {

        boolean started;
        boolean closed;
        int speechStopped;
        int inputSignals;
        int outputAudio;
        long outputAudioBytes;
        int doneSignals;
        long firstOutputMillis = -1;
        long speechStoppedMillis = -1;
        final Set<String> responseIds = new LinkedHashSet<>();
        final Set<String> doneIds = new LinkedHashSet<>();
        final List<ErrorInfo> errors = new ArrayList<>();

        void accept(RawFrame frame, long elapsedMillis) {
            String type = frame.type();
            if (type.equals(CLOSED_MARK) || type.equals("session.closed")) {
                closed = true;
            } else if (type.equals("session.started")) {
                started = true;
            } else if (type.contains("speech_stopped")) {
                speechStopped++;
                inputSignals++;
                if (speechStoppedMillis < 0) {
                    speechStoppedMillis = elapsedMillis;
                }
            } else if (type.contains("speech_started") || type.startsWith("session.input_transcript")) {
                inputSignals++;
            } else if (OUTPUT_DELTA_TYPES.contains(type)) {
                if (firstOutputMillis < 0) {
                    firstOutputMillis = elapsedMillis;
                }
                if (frame.responseId() != null) {
                    responseIds.add(frame.responseId());
                }
                if (type.equals("session.output_audio.delta")) {
                    outputAudio++;
                    outputAudioBytes += frame.audioBytes();
                }
            } else if (type.endsWith(".done") && (type.contains("output") || type.contains("response"))) {
                doneSignals++;
                if (frame.responseId() != null) {
                    doneIds.add(frame.responseId());
                }
            }
            if (frame.error() != null) {
                errors.add(frame.error());
            }
        }

        boolean outputSettled() {
            return outputAudio > 0 && (responseIds.isEmpty() ? doneSignals > 0 : doneIds.containsAll(responseIds));
        }
    }

    /** 운영 해석기가 알아들은 사건 개수. */
    static final class Parsed {

        int started;
        int speechStopped;
        int outputAudio;
        long outputAudioBytes;
        int outputDone;

        void drain(LinkedBlockingQueue<ProviderEvent> events) {
            ProviderEvent event;
            while ((event = events.poll()) != null) {
                switch (event) {
                    case ProviderEvent.Started started -> this.started++;
                    case ProviderEvent.SpeechStopped stopped -> speechStopped++;
                    case ProviderEvent.OutputAudioDelta delta -> {
                        outputAudio++;
                        outputAudioBytes += delta.audio().length;
                    }
                    case ProviderEvent.OutputDone done -> outputDone++;
                    default -> {
                        // 그 밖의 사건은 세지 않는다
                    }
                }
            }
        }
    }

    /** 한 형식으로 연결 → 시작 → 입력 → 지시 → 종료를 관측한다. */
    Attempt attempt(Mode mode, URI url, String key, InputAudio input) {
        Tap tap = new Tap(json);
        Link link;
        long openStart = System.nanoTime();
        try {
            link = opener.open(url, key, tap);
        } catch (Exception e) {
            List<Row> rows = new ArrayList<>();
            for (String item : ITEMS) {
                rows.add(new Row(item, "연결 실패(" + failureName(e) + ")",
                        item.startsWith("①") || item.startsWith("②") ? Verdict.FAIL : Verdict.NOT_OBSERVED));
            }
            return new Attempt(mode, Outcome.FAIL, rows);
        }
        try {
            return observeOpen(mode, link, tap, input, openStart);
        } finally {
            link.session().close();
            try {
                link.resources().close();
            } catch (Exception e) {
                // 정리 경로: 연결 자원 닫기 실패는 결과에 영향이 없다
            }
        }
    }

    /** 송신 하나: 앱 형식은 운영 세션, 키트 형식은 같은 소켓에 문자열. 실패하면 사유 이름. */
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

    private Attempt observeOpen(Mode mode, Link link, Tap tap, InputAudio input, long openStart) {
        ProviderSession session = link.session();
        boolean app = mode == Mode.APP;
        long connectedMillis = millisSince(openStart);
        Stats start = new Stats();

        String startFailure = send(() -> {
            if (app) {
                session.start(factory.sessionStart(new ChildContext("하늘", 8, List.of("블록"))));
            } else {
                sendText(link, kitSessionStart());
            }
        });
        long startSent = System.nanoTime();
        await(tap, start, startSent, STARTED_WAIT, () -> start.started || start.closed || !start.errors.isEmpty());
        long startedMillis = millisSince(startSent);
        List<String> startedKeys = tap.rawSnapshot().stream().filter(frame -> frame.type().equals("session.started"))
                .findFirst().map(RawFrame::keys).orElse(List.of());

        // A. 입력 음성(실시간 간격) → 자동 응답 관측
        Stats phaseA = new Stats();
        phaseA.started = start.started;
        phaseA.closed = start.closed;
        List<byte[]> frames = app ? input.framesAt(APP_RATE, FRAME_BYTES) : input.framesAt(KIT_RATE, KIT_FRAME_BYTES);
        int sent = 0;
        String sendFailure = null;
        long audioStart = System.nanoTime();
        if (start.started && startFailure == null && !start.closed) {
            for (byte[] frame : frames) {
                sendFailure = send(() -> {
                    if (app) {
                        session.sendInputAudio(factory.inputAudio(frame));
                    } else {
                        sendText(link, kitAudioAppend(frame));
                    }
                });
                if (sendFailure != null) {
                    break;
                }
                sent++;
                drainFor(tap, phaseA, audioStart, FRAME_PACE);
                if (phaseA.closed) {
                    sendFailure = "CLOSED";
                    break;
                }
            }
        }
        long audioEnd = System.nanoTime();
        settlePhaseA(tap, phaseA, audioStart);
        boolean autoResponse = phaseA.outputAudio > 0;
        if (autoResponse && !phaseA.outputSettled() && !phaseA.closed) {
            // 자동 응답 정리 실패는 ②에서 그대로 드러난다
            send(() -> {
                if (app) {
                    session.cancelResponse(factory.responseCancel(null));
                } else {
                    sendText(link, json.write(factory.responseCancel(null)));
                }
            });
            drainFor(tap, phaseA, audioStart, SETTLE_QUIET);
        }

        // B. 지시 교정 + 응답 생성 → 새 응답
        Stats phaseB = new Stats();
        int rawMarkB = tap.rawCount();
        long instructStart = System.nanoTime();
        String instructionFailure;
        if (phaseA.closed || !start.started) {
            instructionFailure = "CLOSED";
        } else {
            instructionFailure = send(() -> {
                if (app) {
                    session.correctInstructions(factory.instructionsCorrect(DialogInstructions.Correction.INCOMPLETE));
                    session.createResponse(factory.responseCreate());
                } else {
                    sendText(link, json.write(factory.instructionsCorrect(DialogInstructions.Correction.INCOMPLETE)));
                    sendText(link, json.write(factory.responseCreate()));
                }
            });
        }
        if (instructionFailure == null) {
            settlePhaseB(tap, phaseB, phaseA.responseIds, instructStart);
        }
        Set<String> newIds = new LinkedHashSet<>(phaseB.responseIds);
        newIds.removeAll(phaseA.responseIds);

        // C. 종료 형식
        Stats phaseC = new Stats();
        int rawMarkC = tap.rawCount();
        long closeStart = System.nanoTime();
        String closeFailure = null;
        boolean closedEarly = phaseA.closed || phaseB.closed || start.closed;
        if (!closedEarly) {
            closeFailure = send(() -> sendText(link, app ? json.write(factory.sessionClose()) : kitSessionClose()));
            await(tap, phaseC, closeStart, CLOSE_WAIT, () -> phaseC.closed);
        }
        long closeMillis = millisSince(closeStart);
        Parsed parsed = new Parsed();
        parsed.drain(tap.events);
        List<RawFrame> raw = tap.rawSnapshot();
        boolean closedMessage = raw.stream().skip(rawMarkC).anyMatch(frame -> frame.type().equals("session.closed"));

        List<Row> rows = new ArrayList<>();

        // ① 서버 쪽 음성 전송 · 출력 수신
        long totalAudioBytes = phaseA.outputAudioBytes + phaseB.outputAudioBytes;
        boolean one = start.started && startFailure == null && sendFailure == null && sent == frames.size()
                && phaseA.inputSignals > 0 && totalAudioBytes > 0 && (!app || parsed.outputAudioBytes > 0);
        StringBuilder first = new StringBuilder()
                .append("연결 ").append(connectedMillis).append("ms · session.started ")
                .append(start.started ? startedMillis + "ms" : "없음")
                .append(startFailure == null ? "" : "(start 송신 실패 " + startFailure + ")")
                .append(" · 입력 ").append(app ? APP_RATE : KIT_RATE).append("Hz 프레임 ").append(sent).append('/')
                .append(frames.size()).append(" 송신").append(sendFailure == null ? "" : "(실패 " + sendFailure + ")")
                .append(" · 입력 인식 신호 ").append(phaseA.inputSignals).append("건")
                .append(" · 출력 음성 조각 ").append(phaseA.outputAudio + phaseB.outputAudio).append("개(자동 ")
                .append(phaseA.outputAudio).append(" · 지시 뒤 ").append(phaseB.outputAudio).append(", ")
                .append(totalAudioBytes).append("바이트)");
        if (app) {
            first.append(" · 앱 해석기 출력 음성 ").append(parsed.outputAudioBytes).append("바이트");
        }
        if (!input.fromEnv()) {
            first.append(" — env ").append(INPUT_PCM_ENV).append(" 미지정이라 저장소 자리 파일(합성 무음 · 톤)을 썼다. 키트의 cache/ 폴더에")
                    .append(" Piper로 만든 합성 음성(.pcm, 16kHz 원시)이 있으면 그 경로를 env ").append(INPUT_PCM_ENV)
                    .append("로, env ").append(INPUT_RATE_ENV).append("=16000과 함께 주고 다시 돌린다");
        }
        rows.add(new Row(ITEMS.get(0), first.toString(), one ? Verdict.PASS : Verdict.FAIL));

        // ② 지시 교정 + 응답 생성 뒤 응답
        boolean two = instructionFailure == null && phaseB.outputAudioBytes > 0 && phaseB.errors.isEmpty();
        String second = instructionFailure != null
                ? "correct · create 송신 실패(" + instructionFailure + ")"
                : "correct · create 송신 OK · 새 응답 id " + newIds.size() + "개 · 음성 조각 " + phaseB.outputAudio + "개("
                        + phaseB.outputAudioBytes + "바이트) · 완료 신호 " + phaseB.doneSignals + "건 · 첫 조각까지 "
                        + (phaseB.firstOutputMillis < 0 ? "없음" : phaseB.firstOutputMillis + "ms")
                        + (phaseB.errors.isEmpty() ? "" : " · 이 단계 오류 " + describeErrors(phaseB.errors));
        rows.add(new Row(ITEMS.get(1), second, two ? Verdict.PASS : Verdict.FAIL));

        // ③ 말 끝 신호
        String third = (phaseA.speechStopped == 0 ? "0건" : phaseA.speechStopped + "건 · 음성 송신 시작부터 첫 신호 "
                + phaseA.speechStoppedMillis + "ms") + " (입력 소리 길이 " + input.millis() + "ms · 송신 전체 "
                + millisBetween(audioStart, audioEnd) + "ms)";
        rows.add(new Row(ITEMS.get(2), third, phaseA.speechStopped > 0 ? Verdict.PASS : Verdict.NOT_OBSERVED));

        // ④ 종료 형식
        String fourth = closedEarly
                ? "확인 전에 연결이 먼저 닫힘(아래 '연결 끊김' 참고)"
                : "session.close 송신" + (closeFailure == null ? "" : " 실패(" + closeFailure + ")") + " → session.closed 메시지 "
                        + (closedMessage ? "있음" : "없음") + " · " + (phaseC.closed ? closeMillis + "ms에 닫힘" : CLOSE_WAIT.toMillis()
                                + "ms 안에 닫힘 없음");
        rows.add(new Row(ITEMS.get(3), fourth, !closedEarly && phaseC.closed && closeFailure == null ? Verdict.PASS : Verdict.NOT_OBSERVED));

        // ⑤ 응답 생성 확인 사건 · 자동 응답 막기
        rows.add(new Row(ITEMS.get(4), instructionFailure != null ? "지시 송신 실패로 확인 못 함"
                : responseCreatedCandidates(raw, rawMarkB, newIds), Verdict.INFO));
        rows.add(new Row(ITEMS.get(5), "입력만 보냈을 때(create 없이) 자동 응답 음성 " + phaseA.outputAudio + "조각 · session.started 키 "
                + startedKeys + " — 막기 수단은 공급자 문서로 사람이 확인", Verdict.INFO));

        // 오류 사건
        List<ErrorInfo> errors = new ArrayList<>(start.errors);
        errors.addAll(phaseA.errors);
        errors.addAll(phaseB.errors);
        errors.addAll(phaseC.errors);
        rows.add(new Row(ITEMS.get(6), errors.isEmpty() ? "없음" : errors.size() + "건 — " + describeErrors(errors),
                errors.isEmpty() ? Verdict.INFO : Verdict.FAIL));

        // 지원 type 목록(오류 문장에서, 200자 자르기 · 가림 전 원문 기준)
        rows.add(new Row(ITEMS.get(7), describeTypeLists(errors), Verdict.INFO));

        // 수신 사건 모양(type별 첫 사건)
        rows.add(new Row(ITEMS.get(8), describeShapes(raw), Verdict.INFO));

        // 연결 끊김 · 마지막 사건
        rows.add(new Row(ITEMS.get(9), describeClose(tap, closedEarly, closedMessage), Verdict.INFO));

        // 앱 해석기 이해
        rows.add(new Row(ITEMS.get(10), "session.started " + parsed.started + "건 · speech_stopped " + parsed.speechStopped
                + "건 · 출력 음성 " + parsed.outputAudio + "조각(" + parsed.outputAudioBytes + "바이트) · output.done " + parsed.outputDone
                + "건 (원시 출력 음성 " + totalAudioBytes + "바이트)", Verdict.INFO));

        // 앱이 모르는 type
        Map<String, Integer> unknown = new TreeMap<>();
        raw.stream().filter(frame -> !KNOWN_TYPES.contains(frame.type()) && !frame.type().equals(CLOSED_MARK))
                .forEach(frame -> unknown.merge(frame.type(), 1, Integer::sum));
        rows.add(new Row(ITEMS.get(11), unknown.isEmpty() ? "없음" : unknown.toString(), Verdict.INFO));

        // 사용량
        rows.add(new Row(ITEMS.get(12), describeUsage(raw, tap.sessionMillis()), Verdict.INFO));

        return new Attempt(mode, one && two ? Outcome.PASS : Outcome.FAIL, rows);
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

    private static String describeClose(Tap tap, boolean closedEarly, boolean closedMessage) {
        Integer code = tap.closeCode();
        String error = tap.socketError();
        String how;
        if (code != null) {
            how = "닫힘 코드 " + code;
        } else if (error != null) {
            how = "close 프레임 없이 끊김(1006 해당, " + error + ")";
        } else {
            how = "소켓 닫힘 신호 없음(정리 단계에서 이 도구가 닫음)";
        }
        String last = tap.lastTypeBeforeClose();
        return (closedEarly ? "확인 전에 공급자 쪽에서 끊김 · " : "") + how + " · session.closed 메시지 " + (closedMessage ? "있음" : "없음")
                + " · 끊기기 전 마지막 사건 " + (last == null ? "해당 없음" : last);
    }

    /** create 송신 뒤 새 응답의 첫 출력 조각 전에 온 모르는 type(응답 id · 키 이름). */
    static String responseCreatedCandidates(List<RawFrame> raw, int fromSeq, Set<String> newIds) {
        List<String> candidates = new ArrayList<>();
        for (RawFrame frame : raw) {
            if (frame.seq() < fromSeq) {
                continue;
            }
            if (OUTPUT_DELTA_TYPES.contains(frame.type()) && (frame.responseId() == null || newIds.contains(frame.responseId()))) {
                break;
            }
            if (!KNOWN_TYPES.contains(frame.type()) && !frame.type().equals(CLOSED_MARK) && !frame.type().equals("error")) {
                boolean sameId = frame.responseId() != null && newIds.contains(frame.responseId());
                candidates.add(frame.type() + "(responseId " + (frame.responseId() == null ? "없음" : sameId ? "새 응답과 같음" : "다름")
                        + ", 키 " + frame.keys() + ")");
            }
        }
        return candidates.isEmpty() ? "없음 — 첫 조각 전에 앱이 모르는 type이 오지 않았다" : "후보 " + String.join(", ", candidates);
    }

    private void settlePhaseA(Tap tap, Stats stats, long phaseStart) {
        long deadline = System.nanoTime() + PHASE_A_WAIT.toNanos();
        long lastEvent = System.nanoTime();
        while (System.nanoTime() < deadline && !stats.closed) {
            RawFrame frame = poll(tap, POLL);
            long now = System.nanoTime();
            if (frame != null) {
                stats.accept(frame, millisBetween(phaseStart, now));
                lastEvent = now;
                continue;
            }
            long quiet = now - lastEvent;
            if (stats.outputSettled() && quiet >= SETTLE_QUIET.toNanos()) {
                return;
            }
            if (stats.outputAudio == 0 && quiet >= NO_OUTPUT_QUIET.toNanos()) {
                return;
            }
        }
    }

    /** 새 응답의 음성이 오고 완료 신호가 오거나(앱) 조용해질 때까지, 또는 시한까지. */
    private void settlePhaseB(Tap tap, Stats stats, Set<String> oldIds, long phaseStart) {
        long deadline = System.nanoTime() + PHASE_B_WAIT.toNanos();
        long lastEvent = System.nanoTime();
        while (System.nanoTime() < deadline && !stats.closed) {
            RawFrame frame = poll(tap, POLL);
            long now = System.nanoTime();
            if (frame != null) {
                stats.accept(frame, millisBetween(phaseStart, now));
                lastEvent = now;
                if (stats.doneIds.stream().anyMatch(id -> !oldIds.contains(id)) && stats.outputAudio > 0) {
                    return;
                }
                continue;
            }
            if (stats.outputAudio > 0 && now - lastEvent >= SETTLE_QUIET.toNanos()) {
                return;
            }
        }
    }

    private static void await(Tap tap, Stats stats, long phaseStart, Duration limit, BooleanSupplier done) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            RawFrame frame = poll(tap, POLL);
            if (frame != null) {
                stats.accept(frame, millisSince(phaseStart));
            }
        }
    }

    private static void drainFor(Tap tap, Stats stats, long phaseStart, Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            RawFrame frame = poll(tap, Duration.ofNanos(remaining));
            if (frame != null) {
                stats.accept(frame, millisSince(phaseStart));
            }
        }
    }

    private static RawFrame poll(Tap tap, Duration wait) {
        try {
            return tap.rawQueue.poll(wait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("실호출 확인이 중단됐다", e);
        }
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

    /** 보고서 본문(맨 위 outcome은 앱 형식 기준, kitFormat 줄은 비교용, 표는 항목 · 앱 형식 · 키트 형식). */
    static String renderReport(Result result, InputAudio input, String nowUtc) {
        boolean skipped = result.outcome() == Outcome.SKIPPED_NO_KEY;
        StringBuilder out = new StringBuilder();
        out.append("# GPT-Live 격리 실호출 보고서 (U11 W7 · FR9.1 · BR9.2)\n\n");
        out.append("outcome=").append(result.outcome()).append(" evidence=").append(skipped ? "NONE" : "REAL_API_SYNTHETIC").append('\n');
        out.append("kitFormat=").append(result.kitOutcome()).append("\n\n");
        out.append("- ").append(PRICE_NOTE).append('\n');
        out.append("- 실행 시각(UTC): ").append(nowUtc).append('\n');
        if (skipped) {
            out.append("- 증거 종류: 없음(실호출 안 함). SKIPPED_NO_KEY는 통과로 보지 않는다(BR9.1).\n");
        } else {
            out.append("- 증거 종류: [실호출-합성] 실제 API · 합성 음성(R-M11). 테스트 대역 · 실제 기기 증거가 아니다.\n");
            out.append("- 모델(코드 상수): ").append(ProviderRequestFactory.MODEL).append('\n');
        }
        if (input != null) {
            out.append("- 입력 음성: ").append(input.sourceLabel()).append(" · ").append(input.millis()).append("ms (앱 형식 ")
                    .append(APP_RATE).append("Hz · 키트 형식 ").append(KIT_RATE).append("Hz로 맞춰 보냄, 뒤 무음 ")
                    .append(TRAILING_SILENCE_FRAMES).append("프레임)\n");
        }
        out.append("- outcome은 앱 형식(운영 선 형식) 시도의 ① · ② 기준이다. kitFormat은 독립 키트 형식으로 같은 실행에서 한 번 더 시도한 비교 결과다.\n");
        out.append("- 계속 · 중단은 사람이 정한다(BR9.2: 앱 형식 ① · ②가 FAIL이면 U11 구현을 멈추고 ADR-007을 다시 정한다).\n");
        out.append("- 이 보고서에는 전사 원문 · 음성 · 키 · 주소를 담지 않는다. 오류 문장은 앞 ").append(ERROR_MESSAGE_MAX)
                .append("자까지 가려서 남긴다(R-F3 · R-F14).\n\n");
        out.append("| 항목 | 앱 형식 | 키트 형식 |\n|---|---|---|\n");
        out.append("| ").append(cell(result.hostRule().item())).append(" | ").append(cellOf(result.hostRule())).append(" | ")
                .append(cellOf(result.hostRule())).append(" |\n");
        for (int i = 0; i < result.app().rows().size(); i++) {
            Row appRow = result.app().rows().get(i);
            Row kitRow = i < result.kit().rows().size() ? result.kit().rows().get(i) : new Row(appRow.item(), "없음", Verdict.INFO);
            out.append("| ").append(cell(appRow.item())).append(" | ").append(cellOf(appRow)).append(" | ").append(cellOf(kitRow))
                    .append(" |\n");
        }
        return out.toString();
    }

    private static String cellOf(Row row) {
        return "**" + row.verdict() + "** · " + cell(row.observation());
    }

    private static String cell(String value) {
        return value.replace("|", "/").replace("\n", " ");
    }

    /** 사람이 읽는 한 줄 요약(원문 없음). */
    static String summary(Result result) {
        return "GPT-Live 실호출 앱 형식 " + result.outcome() + " · 키트 형식 " + result.kitOutcome() + " — 앱 "
                + verdicts(result.app()) + " / 키트 " + verdicts(result.kit());
    }

    private static String verdicts(Attempt attempt) {
        return String.join(" ", attempt.rows().stream().limit(4).map(row -> row.item().split(" ")[0] + "=" + row.verdict()).toList());
    }

    static boolean anyVerdict(Result result, Verdict verdict) {
        return result.hostRule().verdict() == verdict
                || result.app().rows().stream().anyMatch(row -> row.verdict() == verdict)
                || result.kit().rows().stream().anyMatch(row -> row.verdict() == verdict);
    }
}
