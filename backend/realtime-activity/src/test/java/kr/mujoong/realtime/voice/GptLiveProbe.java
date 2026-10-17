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
 * <p>한 실행의 흐름: 연결 → {@code session.start}(표본율 env {@value #RATE_ENV}, 기본 24000) → 합성 말소리를 실시간 간격으로 여러 발화 보내며
 * 자동 응답을 관찰 → 고정 지시문 {@code instructions.append} 두 번 → {@code response.create} → (선택) {@code session.update} → {@code session.close}.
 * 보고서는 W7 확인 ① ~ ⑨를 항목마다 판정 · 관측으로 남긴다. 사람이 이 결과로 계속 · 중단을 정한다(BR9.2).
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
    /** 자동 응답을 관찰하려고 보내는 발화 수 env 이름(비밀 아님, 기본 {@value #DEFAULT_UTTERANCES}, 1 ~ {@value #MAX_UTTERANCES}). */
    static final String UTTERANCES_ENV = "GPT_LIVE_UTTERANCES";
    /** 자동 응답을 끄는 {@code session.update} 메시지 JSON env 이름(비밀 아님, 없으면 이 시험은 건너뛴다 — 필드 모양을 모른다, W7 ⑨). */
    static final String UPDATE_JSON_ENV = "GPT_LIVE_UPDATE_JSON";
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
    /** 기본 발화 수. */
    static final int DEFAULT_UTTERANCES = 3;
    /** 발화 수 상한(비용). */
    static final int MAX_UTTERANCES = 10;
    /** 오류 문장을 보고서에 남기는 최대 길이. */
    static final int ERROR_MESSAGE_MAX = 200;
    /** 구간 끊김 시간 800ms(timing.md {@code mujoong.gate.segment-quiet})와 비교하는 쉼 길이 기준. */
    static final long SEGMENT_QUIET_MILLIS = 800L;
    /** 자동 응답 시작 대기 + 끊김 시간이 응답 준비 대기의 절반(5초)을 넘으면 사람과 다시 정한다(설계 W7 ⑧). */
    static final long HALF_RESPONSE_READY_MILLIS = 5_000L;
    /** ⑧ 값 제안에 더하는 여유(밀리초). */
    static final long AUTO_START_MARGIN_MILLIS = 300L;

    // 실호출 시험 전용 대기 시간(운영 코드가 아니라 TimeLiteralScanTest 대상 밖). 연결 시한은 운영 값 mujoong.provider.connect-timeout과 같다
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    /** 기다리는 시간 묶음(시험이 줄여 넣을 수 있다). */
    record Timing(Duration framePace, Duration startedWait, Duration settleQuiet, Duration noOutputQuiet, Duration phaseWait,
            Duration closeWait, Duration poll, int trailingSilenceFrames) {

        /** 실호출 기본: 20ms 간격 · 시작 확인 10초 · 응답이 끝난 뒤 2초 조용 · 출력이 없으면 6초 · 한 단계 20초 · 닫힘 5초. */
        static final Timing REAL = new Timing(Duration.ofMillis(20), Duration.ofSeconds(10), Duration.ofSeconds(2),
                Duration.ofSeconds(6), Duration.ofSeconds(20), Duration.ofSeconds(5), Duration.ofMillis(100),
                TRAILING_SILENCE_FRAMES);
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
            writeReport(skipped, null, 0);
            return skipped;
        }
        int rate = sessionRate(env.apply(RATE_ENV));
        int utterances = utteranceCount(env.apply(UTTERANCES_ENV));
        String updateJson = updateMessage(env.apply(UPDATE_JSON_ENV));
        URI url = providerUrl();
        Optional<ProviderHosts.Problem> problem = ProviderHosts.check(url, "demo");
        if (problem.isPresent()) {
            Row host = new Row("공급자 주소 규칙", "demo 규칙(ProviderHosts) 위반: " + problem.get(), Verdict.FAIL);
            Result blocked = new Result(Outcome.FAIL, host, ITEMS.stream().map(item -> new Row(item, "주소 규칙 위반으로 연결 안 함",
                    Verdict.FAIL)).toList(), rate);
            writeReport(blocked, null, utterances);
            return blocked;
        }
        Row host = new Row("공급자 주소 규칙", "demo 프로필 주소가 ProviderHosts demo 규칙(wss · 허용 호스트) 통과", Verdict.PASS);
        InputAudio input = loadInput();
        Result result = attempt(host, url, key, input, rate, utterances, updateJson);
        writeReport(result, input, utterances);
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

    /** 발화 수(env {@value #UTTERANCES_ENV}, 없으면 {@value #DEFAULT_UTTERANCES}). */
    static int utteranceCount(String value) throws IOException {
        if (value == null || value.isBlank()) {
            return DEFAULT_UTTERANCES;
        }
        try {
            int count = Integer.parseInt(value.strip());
            if (count < 1 || count > MAX_UTTERANCES) {
                throw new IOException(UTTERANCES_ENV + "는 1 ~ " + MAX_UTTERANCES + "이다");
            }
            return count;
        } catch (NumberFormatException e) {
            throw new IOException(UTTERANCES_ENV + "가 정수가 아니다", e);
        }
    }

    /** {@code session.update} 메시지 env(JSON 객체이고 type이 {@code session.update}여야 한다). 없으면 null. */
    String updateMessage(String value) throws IOException {
        if (value == null || value.isBlank()) {
            return null;
        }
        Map<?, ?> parsed;
        try {
            parsed = json.read(value.strip(), Map.class);
        } catch (RuntimeException e) {
            throw new IOException(UPDATE_JSON_ENV + "가 JSON 객체가 아니다", e);
        }
        if (parsed == null || !"session.update".equals(parsed.get("type"))) {
            throw new IOException(UPDATE_JSON_ENV + "의 type은 session.update여야 한다");
        }
        return value.strip();
    }

    /** 보고서 행 순서. */
    static final List<String> ITEMS = List.of("① 입력 표본율 수락(세션 시작 · 음성 송신 · 출력 수신)", "② 출력 전사 사건",
            "③ 입력 전사 조각 · 완료 사건", "④ 말 끝 사건", "⑤ 응답 끝 · 응답 경계 사건", "⑥ instructions.append 누적",
            "⑦ append + create 뒤 응답 · 지원 목록 밖 type 오류", "⑧ 말 끝 → 자동 응답 첫 조각 시간",
            "⑨ 자동 응답 안 쉼 길이 · session.update로 끄기", "종료 형식(session.close → session.closed)", "오류 사건(error)",
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
            String usage, long atNanos, int instructionsLength) {

        RawFrame(int seq, String type, String responseId, List<String> keys) {
            this(seq, type, responseId, keys, 0, null, "-", null, 0L, -1);
        }

        RawFrame(int seq, String type, String responseId, List<String> keys, int audioBytes, ErrorInfo error, String shape,
                String usage) {
            this(seq, type, responseId, keys, audioBytes, error, shape, usage, 0L, -1);
        }

        /** 받은 시각(단조 나노초)을 붙인 사본. */
        RawFrame at(long nanos) {
            return new RawFrame(seq, type, responseId, keys, audioBytes, error, shape, usage, nanos, instructionsLength);
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
        int instructionsLength = fields.get("session") instanceof Map<?, ?> session && session.get("instructions") instanceof String text
                ? text.length() : -1;
        return new RawFrame(seq, type, responseId, keys, audioBytes, error, shapeOf(fields, 0), usage, 0L, instructionsLength);
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
            RawFrame frame = parseRaw(json, rawCount(), message).at(System.nanoTime());
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

    static String safe(String token) {
        return token != null && SAFE_TOKEN.matcher(token).matches() ? token : "(형식 밖)";
    }

    // ---- 관측 ----

    /** 한 발화(입력 음성을 보내고 자동 응답을 기다린 구간). 시각은 단조 나노초다. */
    record Utterance(long startNanos, long lastVoicedNanos, long endNanos, int sentFrames, int totalFrames, String sendFailure) {
    }

    /** {@code session.update} 시험 관측(env가 있을 때만). */
    record UpdateTrial(String sendFailure, long sentNanos, long endNanos) {
    }

    /** 한 실행의 원자료. 보고서 행은 이것과 원시 관측({@link RawFrame})만으로 계산한다({@link #buildRows}). */
    static final class Obs {

        int rate;
        InputAudio input;
        long connectedMillis;
        String startFailure;
        long startedMillis = -1;
        final List<Utterance> utterances = new ArrayList<>();
        String appendFailure;
        int appendsSent;
        long appendTextChars;
        String createFailure;
        long createMarkNanos;
        long createEndNanos;
        UpdateTrial update;
        String updateEnv;
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
    }

    /** 연결 → 시작 → 발화들 → 지시 추가 → 응답 생성 → (선택) 갱신 → 종료를 관측한다. */
    Result attempt(Row host, URI url, String key, InputAudio input, int rate, int utterances, String updateJson) {
        Tap tap = new Tap(json);
        long openStart = System.nanoTime();
        Link link;
        try {
            link = opener.open(url, key, tap);
        } catch (Exception e) {
            return new Result(Outcome.FAIL, host, ITEMS.stream().map(item -> new Row(item, "연결 실패(" + failureName(e) + ")",
                    item.startsWith("①") || item.startsWith("⑦") ? Verdict.FAIL : Verdict.NOT_OBSERVED)).toList(), rate);
        }
        try {
            Obs obs = observe(link, tap, input, rate, utterances, updateJson, openStart);
            List<Row> rows = buildRows(obs);
            boolean one = rows.get(0).verdict() == Verdict.PASS;
            boolean seven = rows.get(6).verdict() == Verdict.PASS;
            return new Result(one && seven ? Outcome.PASS : Outcome.FAIL, host, rows, rate);
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

    private Obs observe(Link link, Tap tap, InputAudio input, int rate, int utterances, String updateJson, long openStart) {
        ProviderSession session = link.session();
        Obs obs = new Obs();
        obs.rate = rate;
        obs.input = input;
        obs.connectedMillis = millisSince(openStart);

        // A. 세션 시작(표본율 지정)
        obs.startFailure = send(() -> session.start(factory.sessionStart(new ChildContext("하늘", 8, List.of("블록")), rate)));
        long startSent = System.nanoTime();
        waitFor(tap, startSent, timing.startedWait(), () -> tap.has("session.started") || tap.closed() || tap.has("error"));
        boolean started = tap.has("session.started");
        obs.startedMillis = started ? millisSince(startSent) : -1;

        // B. 발화들(실시간 간격) → 자동 응답 관찰
        int frameBytes = rate / 50 * 2;
        List<byte[]> frames = input.framesAt(rate, frameBytes, timing.trailingSilenceFrames());
        int voiced = frames.size() - timing.trailingSilenceFrames();
        for (int i = 0; i < utterances && started && obs.startFailure == null && !tap.closed(); i++) {
            obs.utterances.add(speak(session, tap, frames, voiced, rate));
        }

        // C. 고정 지시문 두 번(누적 관찰) → 응답 생성
        if (started && !tap.closed() && obs.startFailure == null) {
            DialogInstructions.Correction first = DialogInstructions.Correction.INCOMPLETE;
            DialogInstructions.Correction second = DialogInstructions.Correction.PRESSURE;
            obs.appendFailure = send(() -> session.appendInstructions(factory.instructionsAppend(first)));
            if (obs.appendFailure == null) {
                obs.appendsSent = 1;
                obs.appendTextChars = first.text().length();
                sleep(timing.settleQuiet().dividedBy(2));
                obs.appendFailure = send(() -> session.appendInstructions(factory.instructionsAppend(second)));
                if (obs.appendFailure == null) {
                    obs.appendsSent = 2;
                    obs.appendTextChars += second.text().length();
                    sleep(timing.settleQuiet().dividedBy(2));
                }
            }
            obs.createMarkNanos = System.nanoTime();
            obs.createFailure = obs.appendFailure != null ? "append 실패" : send(() -> session.createResponse(factory.responseCreate()));
            if (obs.createFailure == null) {
                settle(tap, obs.createMarkNanos);
            }
            obs.createEndNanos = System.nanoTime();
        }

        // D. (선택) 자동 응답 끄기 시험: 사람이 준 session.update를 보내고 발화 하나를 더 보낸다
        if (updateJson != null && started && !tap.closed()) {
            obs.updateEnv = "있음";
            long sent = System.nanoTime();
            String failure = send(() -> sendText(link, updateJson));
            if (failure == null) {
                sleep(timing.settleQuiet().dividedBy(2));
                obs.utterances.add(speak(session, tap, frames, voiced, rate));
            }
            obs.update = new UpdateTrial(failure, sent, System.nanoTime());
        }

        // E. 종료 형식
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

    /** 발화 하나를 실시간 간격으로 보내고 자동 응답이 끝나기(또는 오지 않기)를 기다린다. */
    private Utterance speak(ProviderSession session, Tap tap, List<byte[]> frames, int voiced, int rate) {
        long start = System.nanoTime();
        long lastVoiced = start;
        int sent = 0;
        String failure = null;
        for (int index = 0; index < frames.size(); index++) {
            byte[] frame = frames.get(index);
            failure = send(() -> session.sendInputAudio(inputAudio(frame, rate)));
            if (failure != null) {
                break;
            }
            sent++;
            if (index < voiced) {
                lastVoiced = System.nanoTime();
            }
            sleep(timing.framePace());
            if (tap.closed()) {
                failure = "CLOSED";
                break;
            }
        }
        if (failure == null) {
            settle(tap, start);
        }
        return new Utterance(start, lastVoiced, System.nanoTime(), sent, frames.size(), failure);
    }

    /**
     * 출력이 끝나기를 기다린다: 이 시각 뒤 출력 음성 조각이 온 적이 있으면 마지막 사건 뒤 {@code settleQuiet}가 지날 때까지, 출력이 한 번도 안 오면
     * {@code noOutputQuiet}가 지날 때까지(둘 다 {@code phaseWait} 상한, 연결이 닫히면 끝).
     */
    private void settle(Tap tap, long sinceNanos) {
        long deadline = System.nanoTime() + timing.phaseWait().toNanos();
        long begin = System.nanoTime();
        while (System.nanoTime() < deadline && !tap.closed()) {
            long now = System.nanoTime();
            long lastAudio = tap.lastAudioNanos();
            boolean heard = lastAudio >= sinceNanos;
            if (heard && now - Math.max(lastAudio, tap.lastFrameNanos()) >= timing.settleQuiet().toNanos()) {
                return;
            }
            if (!heard && now - begin >= timing.noOutputQuiet().toNanos()) {
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

    private static long percentile95(List<Long> sorted) {
        int index = (int) Math.ceil(0.95 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** 발화 구간 안의 출력 음성 조각 시각들(단조 나노초). */
    private static List<Long> audioTimes(List<RawFrame> raw, long fromNanos, long toNanos) {
        return raw.stream().filter(frame -> OUTPUT_AUDIO_TYPES.contains(frame.type()) && frame.atNanos() >= fromNanos
                && frame.atNanos() < toNanos).map(RawFrame::atNanos).sorted().toList();
    }

    /** 보고서 행 열일곱 개({@link #ITEMS} 순서). 판정 · 관측만 담고 전사 · 음성 · 키는 담지 않는다. */
    static List<Row> buildRows(Obs obs) {
        List<RawFrame> raw = obs.raw;
        List<Row> rows = new ArrayList<>();
        List<ErrorInfo> errors = raw.stream().map(RawFrame::error).filter(java.util.Objects::nonNull).toList();
        boolean started = raw.stream().anyMatch(frame -> frame.type().equals("session.started"));
        long audioChunks = raw.stream().filter(frame -> OUTPUT_AUDIO_TYPES.contains(frame.type())).count();
        long audioBytes = raw.stream().filter(frame -> OUTPUT_AUDIO_TYPES.contains(frame.type())).mapToLong(RawFrame::audioBytes).sum();
        long unsupportedTypeErrors = errors.stream().filter(error -> "type".equals(error.param())).count();
        int totalFrames = obs.utterances.stream().mapToInt(Utterance::totalFrames).sum();
        int sentFrames = obs.utterances.stream().mapToInt(Utterance::sentFrames).sum();
        boolean sendsOk = !obs.utterances.isEmpty() && obs.utterances.stream().allMatch(u -> u.sendFailure() == null);
        long other = errors.size() - unsupportedTypeErrors;

        // ① 표본율 수락
        boolean one = started && obs.startFailure == null && sendsOk && audioBytes > 0 && other == 0;
        StringBuilder first = new StringBuilder()
                .append("입력 ").append(obs.rate).append("Hz · 연결 ").append(obs.connectedMillis).append("ms · session.started ")
                .append(started ? obs.startedMillis + "ms" : "없음")
                .append(obs.startFailure == null ? "" : "(start 송신 실패 " + obs.startFailure + ")")
                .append(" · 발화 ").append(obs.utterances.size()).append("개 프레임 ").append(sentFrames).append('/').append(totalFrames)
                .append(" 송신").append(sendsOk ? "" : "(실패 " + obs.utterances.stream().map(Utterance::sendFailure)
                        .filter(java.util.Objects::nonNull).findFirst().orElse("없음") + ")")
                .append(" · 출력 음성 조각 ").append(audioChunks).append("개(").append(audioBytes).append("바이트)")
                .append(" · 시작·입력 중 오류 ").append(other).append("건");
        if (obs.input != null && !obs.input.fromEnv()) {
            first.append(" — env ").append(INPUT_PCM_ENV).append(" 미지정이라 저장소 자리 파일(합성 무음 · 톤)을 썼다. 합성 말소리 PCM이 있으면 그")
                    .append(" 경로를 env ").append(INPUT_PCM_ENV).append("(원시면 env ").append(INPUT_RATE_ENV).append("=표본율)로 주고 다시 돌린다");
        }
        rows.add(new Row(ITEMS.get(0), first.toString(), one ? Verdict.PASS : Verdict.FAIL));

        // ② 출력 전사 사건
        List<RawFrame> outputTranscripts = framesOfType(raw, GptLiveProbe::isOutputTranscript);
        rows.add(new Row(ITEMS.get(1), outputTranscripts.isEmpty()
                ? "0건 — 출력 전사 사건이 오지 않았다. 구현을 멈추고 사람과 다시 정한다(BR9.2)"
                : outputTranscripts.size() + "건 " + typeSummary(outputTranscripts) + " · 모양 " + describeShapes(outputTranscripts),
                outputTranscripts.isEmpty() ? Verdict.NOT_OBSERVED : Verdict.PASS));

        // ③ 입력 전사 조각 · 완료
        List<RawFrame> inputTranscripts = framesOfType(raw, GptLiveProbe::isInputTranscript);
        rows.add(new Row(ITEMS.get(2), inputTranscripts.isEmpty()
                ? "0건 — 입력 전사가 오지 않았다. 서버 확정(새 시간 키, BR11.9)이 필요하다"
                : inputTranscripts.size() + "건 " + typeSummary(inputTranscripts) + " · 모양 " + describeShapes(inputTranscripts),
                inputTranscripts.isEmpty() ? Verdict.NOT_OBSERVED : Verdict.PASS));

        // ④ 말 끝 사건
        List<RawFrame> speechEnds = framesOfType(raw, GptLiveProbe::isSpeechEnd);
        long speechEndOffset = -1;
        if (!speechEnds.isEmpty() && !obs.utterances.isEmpty()) {
            speechEndOffset = millisBetween(obs.utterances.getFirst().startNanos(), speechEnds.getFirst().atNanos());
        }
        rows.add(new Row(ITEMS.get(3), speechEnds.isEmpty()
                ? "0건 — 말 끝 사건이 오지 않았다. 지연 출처는 입력 전사 확정 · 마지막 입력 프레임이다"
                : speechEnds.size() + "건 " + typeSummary(speechEnds) + " · 첫 발화 송신 시작부터 첫 사건 " + speechEndOffset + "ms (입력 소리 길이 "
                        + (obs.input == null ? 0 : obs.input.millis()) + "ms)",
                speechEnds.isEmpty() ? Verdict.NOT_OBSERVED : Verdict.PASS));

        // ⑤ 응답 끝 · 경계
        String boundaries = boundaryCandidates(raw);
        rows.add(new Row(ITEMS.get(4), boundaries.equals("없음")
                ? "없음 — 응답 끝 · 경계처럼 보이는 모르는 type이 오지 않았다. 끊김(800ms)으로 봉인하는 지금 방식 그대로"
                : "후보 " + boundaries, boundaries.equals("없음") ? Verdict.NOT_OBSERVED : Verdict.INFO));

        // ⑥ instructions.append 누적
        List<Integer> lengths = raw.stream().filter(frame -> frame.instructionsLength() >= 0).map(RawFrame::instructionsLength).toList();
        rows.add(new Row(ITEMS.get(5), obs.appendsSent == 0
                ? "지시 추가를 보내지 못했다(" + obs.appendFailure + ")"
                : "지시 추가 " + obs.appendsSent + "번(고정 문장, 합계 " + obs.appendTextChars + "자) 송신 · "
                        + (lengths.size() < 2 ? "지시문 길이를 싣는 사건이 " + lengths.size() + "건뿐이라 누적 여부를 관측하지 못했다(미확인)"
                                : "지시문 길이를 싣는 사건의 길이 순서 " + lengths + " (늘어난 만큼 누적, 그대로면 교체)"),
                obs.appendsSent > 0 && lengths.size() >= 2 ? Verdict.INFO : Verdict.NOT_OBSERVED));

        // ⑦ append + create 뒤 응답, 지원 목록 밖 type 오류
        long createChunks = audioTimes(raw, obs.createMarkNanos, Long.MAX_VALUE).size();
        long createFirst = audioTimes(raw, obs.createMarkNanos, Long.MAX_VALUE).stream().findFirst()
                .map(at -> millisBetween(obs.createMarkNanos, at)).orElse(-1L);
        boolean seven = obs.appendFailure == null && obs.createFailure == null && createChunks > 0 && unsupportedTypeErrors == 0;
        rows.add(new Row(ITEMS.get(6), (obs.appendFailure != null ? "append 송신 실패(" + obs.appendFailure + ")"
                : obs.createFailure != null ? "create 송신 실패(" + obs.createFailure + ")"
                : "append " + obs.appendsSent + " · create 송신 OK · 새 응답 음성 조각 " + createChunks + "개 · 첫 조각까지 "
                        + (createFirst < 0 ? "없음" : createFirst + "ms"))
                + " · 지원 목록 밖 type 오류 " + unsupportedTypeErrors + "건", seven ? Verdict.PASS : Verdict.FAIL));

        // ⑧ 말 끝 → 자동 응답 첫 조각 시간
        List<Long> latencies = new ArrayList<>();
        int speechEndRef = 0;
        for (int i = 0; i < obs.utterances.size(); i++) {
            Utterance u = obs.utterances.get(i);
            long to = i + 1 < obs.utterances.size() ? obs.utterances.get(i + 1).startNanos() : u.endNanos() + 1;
            long reference = u.lastVoicedNanos();
            for (RawFrame frame : speechEnds) {
                if (frame.atNanos() >= u.startNanos() && frame.atNanos() < to) {
                    reference = frame.atNanos();
                    speechEndRef++;
                    break;
                }
            }
            List<Long> times = audioTimes(raw, u.startNanos(), to);
            if (!times.isEmpty()) {
                latencies.add(Math.max(0L, millisBetween(reference, times.getFirst())));
            }
        }
        if (latencies.isEmpty()) {
            rows.add(new Row(ITEMS.get(7), "자동 응답이 한 번도 오지 않았다(발화 " + obs.utterances.size()
                    + "개) — 값을 segment-quiet와 같게 둔다(BR11.7)", Verdict.NOT_OBSERVED));
        } else {
            List<Long> sorted = latencies.stream().sorted().toList();
            long p95 = percentile95(sorted);
            long suggested = (p95 + AUTO_START_MARGIN_MILLIS + 99) / 100 * 100;
            boolean tooLong = suggested + SEGMENT_QUIET_MILLIS > HALF_RESPONSE_READY_MILLIS;
            rows.add(new Row(ITEMS.get(7), "발화 " + obs.utterances.size() + "개 중 " + latencies.size() + "개에서 자동 응답 · 시간(ms) " + latencies
                    + " · 최소 " + sorted.getFirst() + " · P95 " + p95 + " · 기준 시각은 말 끝 사건 " + speechEndRef + "건 / 마지막 입력 프레임 "
                    + (latencies.size() - speechEndRef) + "건 · 제안 auto-response-start " + suggested + "ms(P95 + 여유 "
                    + AUTO_START_MARGIN_MILLIS + "ms 올림)" + (tooLong ? " — 이 값 + segment-quiet(800ms)가 응답 준비 대기의 절반(5초)을 넘는다. 사람과"
                            + " 다시 정한다" : ""), tooLong ? Verdict.FAIL : Verdict.INFO));
        }

        // ⑨ 쉼 길이 · session.update
        List<Long> longPauses = new ArrayList<>();
        long maxGap = 0;
        for (int i = 0; i < obs.utterances.size(); i++) {
            Utterance u = obs.utterances.get(i);
            long to = i + 1 < obs.utterances.size() ? obs.utterances.get(i + 1).startNanos() : u.endNanos() + 1;
            List<Long> times = audioTimes(raw, u.startNanos(), to);
            for (int t = 1; t < times.size(); t++) {
                long gap = millisBetween(times.get(t - 1), times.get(t));
                maxGap = Math.max(maxGap, gap);
                if (gap >= SEGMENT_QUIET_MILLIS) {
                    longPauses.add(gap);
                }
            }
        }
        String updateText;
        if (obs.updateEnv == null) {
            updateText = "session.update 시험 안 함(env " + UPDATE_JSON_ENV + " 없음 — 메시지 필드 모양을 모른다, 지원 목록에는 session.update가 있다)";
        } else if (obs.update == null || obs.update.sendFailure() != null) {
            updateText = "session.update 송신 실패(" + (obs.update == null ? "없음" : obs.update.sendFailure()) + ")";
        } else {
            long afterUpdate = audioTimes(raw, obs.update.sentNanos(), Long.MAX_VALUE).size();
            updateText = "session.update 송신 뒤 발화 하나의 자동 응답 음성 조각 " + afterUpdate + "개 — "
                    + (afterUpdate == 0 ? "자동 응답이 꺼졌을 수 있다(사람이 확인)" : "자동 응답이 꺼지지 않았다");
        }
        rows.add(new Row(ITEMS.get(8), audioChunks == 0 ? "출력 음성이 없어 쉼을 잴 수 없다 · " + updateText
                : "응답 안 조각 사이 최대 쉼 " + maxGap + "ms · " + SEGMENT_QUIET_MILLIS + "ms 이상 쉼 " + longPauses.size() + "번"
                        + (longPauses.isEmpty() ? "" : " " + longPauses.stream().limit(5).toList()
                                + " — 쉼이 끊김(800ms)과 같거나 길면 한 응답이 둘로 나뉜다. 사람에게 다시 묻는다") + " · " + updateText,
                Verdict.INFO));

        // 종료 형식
        rows.add(new Row(ITEMS.get(9), obs.closedEarly
                ? "확인 전에 연결이 먼저 닫힘(아래 '연결 끊김' 참고)"
                : "session.close 송신" + (obs.closeFailure == null ? "" : " 실패(" + obs.closeFailure + ")") + " → session.closed 메시지 "
                        + (obs.closedMessage ? "있음" : "없음") + " · " + (obs.closeMillis >= 0 && obs.closedMessage
                                ? obs.closeMillis + "ms에 닫힘" : "닫힘 확인 못 함"),
                !obs.closedEarly && obs.closeFailure == null && obs.closedMessage ? Verdict.PASS : Verdict.NOT_OBSERVED));

        // 오류 사건
        rows.add(new Row(ITEMS.get(10), errors.isEmpty() ? "없음" : errors.size() + "건 — " + describeErrors(errors),
                errors.isEmpty() ? Verdict.INFO : Verdict.FAIL));

        // 지원 type 목록(오류 문장에서, 200자 자르기 · 가림 전 원문 기준)
        rows.add(new Row(ITEMS.get(11), describeTypeLists(errors), Verdict.INFO));

        // 수신 사건 모양
        rows.add(new Row(ITEMS.get(12), describeShapes(raw), Verdict.INFO));

        // 연결 끊김 · 마지막 사건
        rows.add(new Row(ITEMS.get(13), describeClose(obs.closeCode, obs.socketError, obs.lastTypeBeforeClose, obs.closedEarly,
                obs.closedMessage), Verdict.INFO));

        // 앱 해석기 이해
        rows.add(new Row(ITEMS.get(14), "session.started " + obs.interpretedStarted + "건 · 출력 음성 " + obs.interpretedAudio + "조각("
                + obs.interpretedAudioBytes + "바이트) · session.usage.updated " + obs.interpretedUsage + "건 · error "
                + obs.interpretedErrors + "건 (원시 출력 음성 " + audioBytes + "바이트)", Verdict.INFO));

        // 앱이 모르는 type
        Map<String, Integer> unknown = new TreeMap<>();
        raw.stream().filter(frame -> !KNOWN_TYPES.contains(frame.type()) && !frame.type().equals(CLOSED_MARK))
                .forEach(frame -> unknown.merge(frame.type(), 1, Integer::sum));
        rows.add(new Row(ITEMS.get(15), unknown.isEmpty() ? "없음" : unknown.toString(), Verdict.INFO));

        // 사용량
        rows.add(new Row(ITEMS.get(16), describeUsage(raw, obs.sessionMillis), Verdict.INFO));
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

    private void writeReport(Result result, InputAudio input, int utterances) throws IOException {
        String text = renderReport(result, input, utterances, new SystemMujoongClock().now().toString());
        if (report.getParent() != null) {
            Files.createDirectories(report.getParent());
        }
        Files.writeString(report, text, StandardCharsets.UTF_8);
    }

    /** 보고서 본문(맨 위 outcome은 ① 입력 표본율 수락과 ⑦ append + create 뒤 응답 기준, 표는 항목 · 판정 · 관측). */
    static String renderReport(Result result, InputAudio input, int utterances, String nowUtc) {
        boolean skipped = result.outcome() == Outcome.SKIPPED_NO_KEY;
        StringBuilder out = new StringBuilder();
        out.append("# GPT-Live 격리 실호출 보고서 (U11 W7 · FR9.1 · BR9.2)\n\n");
        out.append("outcome=").append(result.outcome()).append(" evidence=").append(skipped ? "NONE" : "REAL_API_SYNTHETIC").append('\n');
        out.append("rate=").append(result.rate()).append(" utterances=").append(utterances).append("\n\n");
        out.append("- ").append(PRICE_NOTE).append('\n');
        out.append("- 실행 시각(UTC): ").append(nowUtc).append('\n');
        if (skipped) {
            out.append("- 증거 종류: 없음(실호출 안 함). SKIPPED_NO_KEY는 통과로 보지 않는다(BR9.1).\n");
        } else {
            out.append("- 증거 종류: [실호출-합성] 실제 API · 합성 음성(R-M11). 테스트 대역 · 실제 기기 증거가 아니다.\n");
            out.append("- 모델(코드 상수): ").append(ProviderRequestFactory.MODEL).append('\n');
        }
        if (input != null) {
            out.append("- 입력 음성: ").append(input.sourceLabel()).append(" · ").append(input.millis()).append("ms (운영 팩토리 형식 ")
                    .append(result.rate()).append("Hz로 맞춰 보냄, 발화마다 뒤 무음 ").append(TRAILING_SILENCE_FRAMES).append("프레임)\n");
        }
        out.append("- outcome은 ① 입력 표본율 수락과 ⑦ append + create 뒤 응답(지원 목록 밖 type 오류 0건)이 모두 PASS일 때만 PASS다. 나머지 항목은 계속 ·")
                .append(" 중단을 사람이 정하는 관측이다(BR9.2: ② 출력 전사 사건이 NOT_OBSERVED면 구현을 멈추고 사람과 다시 정한다).\n");
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

    /** 사람이 읽는 한 줄 요약(원문 없음). */
    static String summary(Result result) {
        return "GPT-Live 실호출 " + result.outcome() + " — "
                + String.join(" ", result.rows().stream().limit(9).map(row -> row.item().split(" ")[0] + "=" + row.verdict()).toList());
    }

    static boolean anyVerdict(Result result, Verdict verdict) {
        return result.hostRule().verdict() == verdict || result.rows().stream().anyMatch(row -> row.verdict() == verdict);
    }
}
