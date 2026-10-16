package kr.mujoong.realtime.voice;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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
 * <p>앱 프로필 · 스프링 문맥 없이 운영 송수신 부품을 그대로 쓴다: 요청은 {@link ProviderRequestFactory}, 수신 해석은
 * {@link ProviderClient.Reader}, 송신은 {@link ProviderClient.Session}. 연결 열기만 이 도구가 {@link ProviderClient#open}과 같은 방식(Bearer
 * 헤더 · JVM 기본 프록시)으로 해서, 앱이 모르는 type(⑤ 응답 생성 확인 사건 후보)의 이름을 볼 수 있게 한다. 주소는 demo 프로필 파일의
 * {@code mujoong.provider.url}이고 {@link ProviderHosts}의 demo 규칙(wss · 허용 호스트)을 통과해야만 연결한다(규칙 우회 없음).
 *
 * <p>입력은 합성 음성만 쓴다: env {@code GPT_LIVE_INPUT_PCM}이 가리키는 파일(WAV 또는 PCM16LE 24kHz 모노 원시 파일), 없으면 저장소의 합성음
 * 자리 파일({@value #DEFAULT_INPUT}, 합성 무음 · 톤)을 PCM16LE 24kHz 모노로 읽는다(기능 설계 열린 질문 1, 새 의존성 없이 JDK
 * {@code javax.sound.sampled}).
 *
 * <p>보고서에는 항목 · 관측(개수 · 시간 · type 이름 · 키 이름) · 통과 여부만 쓴다. 전사 원문 · 음성 · 키 · 지시문 원문 · 주소는 쓰지 않는다(R-F3 ·
 * R-F14). 계속 · 중단 판단(BR9.2)은 ① · ②로 하고 ③ ~ ⑤는 판정 턴 구현(Step 10 · BR3.7) 입력으로 보고한다.
 */
final class GptLiveProbe {

    /** 사람이 주는 합성 입력 음성 파일 경로 env 이름(비밀 아님). */
    static final String INPUT_PCM_ENV = "GPT_LIVE_INPUT_PCM";
    /** 보고서 경로 시스템 속성(gptLiveTest 작업이 build/gpt-live/report.md로 넣는다). */
    static final String REPORT_PROPERTY = "mujoong.gpt.live.report";
    /** 기본 입력: 저장소의 합성음 자리 파일(저장소 루트 기준). */
    static final String DEFAULT_INPUT = "infra/seed-audio/intro-free-talk-v2.wav";
    /** 공급자 주소가 있는 프로필 파일(실제 주소 기본값은 demo · prod 파일에만 있다, ai-io.md 2절). */
    static final String DEMO_PROFILE_RESOURCE = "application-demo.yml";
    static final String PROVIDER_URL_KEY = "mujoong.provider.url";

    /** 입력 · 출력 음성 형식 PCM16LE · 24kHz · 모노(J27). */
    static final AudioFormat PCM16_24K_MONO = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 24_000f, 16, 1, 2, 24_000f, false);
    /** 기기 프레임 한 개(20ms) 바이트 수. */
    static final int FRAME_BYTES = AudioFrameCodec.INPUT_PAYLOAD_BYTES;
    /** 말 끝 신호가 나오게 입력 뒤에 붙이는 무음 프레임 수(1.5초). */
    static final int TRAILING_SILENCE_FRAMES = 75;

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

    private static final Set<String> KNOWN_TYPES = Set.of("session.started", "session.input_audio.speech_stopped",
            "session.input_transcript.delta", "session.input_transcript.done", "session.output_transcript.delta",
            "session.output_audio.delta", "session.output.done", "session.error", "session.closed");
    private static final Set<String> OUTPUT_DELTA_TYPES = Set.of("session.output_transcript.delta", "session.output_audio.delta");
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

    /** 실행 결과(① · ② 기준, BR9.2). */
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

    /** 보고서 표 한 줄. 관측에는 원문을 넣지 않는다. */
    record Row(String item, String observation, Verdict verdict) {
    }

    /** 실행 결과와 보고서 표. */
    record Result(Outcome outcome, List<Row> rows) {
    }

    /** 합성 입력 음성(프레임으로 나눈 PCM16LE 24kHz 모노). */
    record InputAudio(String sourceLabel, int pcmBytes, List<byte[]> frames) {
    }

    /** 연결 열기(시험에서 바꿔 끼운다: 키 없음 시험은 이것이 불리지 않음을 단언한다). */
    @FunctionalInterface
    interface Opener {

        Link open(URI url, String apiKey, Tap tap) throws Exception;
    }

    /** 열린 연결: 운영 {@link ProviderClient.Session}과 그 아래 웹소켓(⑤ · ④ 관측용). */
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

    /** 확인을 돌리고 보고서를 쓴다. 키가 없으면 연결하지 않는다. */
    Result run() throws IOException {
        String key = env.apply(ProviderClient.API_KEY);
        if (key == null || key.isBlank()) {
            Result skipped = skipped();
            writeReport(skipped, null, null);
            return skipped;
        }
        List<Row> rows = new ArrayList<>();
        URI url = providerUrl();
        Optional<ProviderHosts.Problem> problem = ProviderHosts.check(url, "demo");
        if (problem.isPresent()) {
            rows.add(new Row("공급자 주소 규칙", "demo 규칙(ProviderHosts) 위반: " + problem.get(), Verdict.FAIL));
            Result failed = new Result(Outcome.FAIL, rows);
            writeReport(failed, null, null);
            return failed;
        }
        rows.add(new Row("공급자 주소 규칙", "demo 프로필 주소가 ProviderHosts demo 규칙(wss · 허용 호스트) 통과", Verdict.PASS));
        InputAudio input = loadInput();
        Result result = observe(url, key, input, rows);
        writeReport(result, input, ProviderRequestFactory.MODEL);
        return result;
    }

    /** 키가 없을 때의 결과(모든 항목 SKIPPED_NO_KEY, 통과 아님). */
    static Result skipped() {
        List<Row> rows = new ArrayList<>();
        for (String item : List.of("① 서버 쪽 음성 전송 · 출력 수신", "② instructions.correct + response.create 뒤 응답",
                "③ speech_stopped", "④ 종료 형식", "⑤ 응답 생성 확인 사건 · 자동 응답 막기")) {
            rows.add(new Row(item, "실호출 안 함(env PROVIDER_API_KEY 없음)", Verdict.SKIPPED_NO_KEY));
        }
        return new Result(Outcome.SKIPPED_NO_KEY, rows);
    }

    // ---- 입력 음성 ----

    /** env 파일이 있으면 그것을, 없으면 저장소 합성음 자리 파일을 PCM16LE 24kHz 모노 프레임으로 읽는다. */
    InputAudio loadInput() throws IOException {
        String configured = env.apply(INPUT_PCM_ENV);
        Path file;
        String label;
        if (configured != null && !configured.isBlank()) {
            file = Path.of(configured.strip());
            label = "env " + INPUT_PCM_ENV + " 파일(" + extensionOf(file) + ")";
        } else {
            file = repoRoot.resolve(DEFAULT_INPUT);
            label = "저장소 합성음 자리 파일 " + DEFAULT_INPUT + "(합성 무음 · 톤)";
        }
        byte[] pcm = readPcm16Mono24k(file);
        return new InputAudio(label, pcm.length, frames(pcm));
    }

    /** WAV면 형식을 확인하고(다르면 JDK 변환), 그 밖은 PCM16LE 24kHz 모노 원시 바이트로 본다. */
    static byte[] readPcm16Mono24k(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("입력 음성 파일이 없다(" + extensionOf(file) + ")");
        }
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav")) {
            byte[] raw = Files.readAllBytes(file);
            if (raw.length == 0 || raw.length % 2 != 0) {
                throw new IOException("원시 PCM16 파일 길이가 0이거나 홀수다");
            }
            return raw;
        }
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat source = in.getFormat();
            if (source.matches(PCM16_24K_MONO)) {
                return in.readAllBytes();
            }
            if (!AudioSystem.isConversionSupported(PCM16_24K_MONO, source)) {
                throw new IOException("PCM16LE 24kHz 모노로 바꿀 수 없는 WAV 형식이다(" + source.getEncoding() + " · "
                        + source.getSampleRate() + "Hz · " + source.getChannels() + "ch)");
            }
            try (AudioInputStream converted = AudioSystem.getAudioInputStream(PCM16_24K_MONO, in)) {
                return converted.readAllBytes();
            }
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("WAV로 읽을 수 없는 파일이다", e);
        }
    }

    /** 960바이트 프레임으로 나누고(마지막은 0으로 채움) 말 끝 신호용 무음 프레임을 붙인다. */
    static List<byte[]> frames(byte[] pcm) {
        List<byte[]> frames = new ArrayList<>();
        for (int offset = 0; offset < pcm.length; offset += FRAME_BYTES) {
            byte[] frame = new byte[FRAME_BYTES];
            System.arraycopy(pcm, offset, frame, 0, Math.min(FRAME_BYTES, pcm.length - offset));
            frames.add(frame);
        }
        for (int i = 0; i < TRAILING_SILENCE_FRAMES; i++) {
            frames.add(new byte[FRAME_BYTES]);
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
     * {@link ProviderClient#open}과 같은 방식으로 연다(JDK HttpClient · 가상 스레드 · JVM 기본 프록시 · Bearer 헤더). 수신은 {@link Tap}이 type
     * 이름만 적고 운영 {@link ProviderClient.Reader}에 그대로 넘기며, 송신은 운영 {@link ProviderClient.Session}이 한다.
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

    /** 받은 원시 메시지의 type 이름 · 응답 id · 키 이름(값 없음). */
    record RawFrame(int seq, String type, String responseId, List<String> keys) {
    }

    /** 받은 메시지 모양 중 이 도구가 보는 필드. */
    record RawInbound(String type, String responseId) {
    }

    /**
     * 수신 갈래: 메시지마다 type 이름 · 응답 id · 키 이름만 적고 운영 {@link ProviderClient.Reader}에 그대로 넘긴다(해석 · 다음 요청은 운영
     * 코드가 한다). 본문 값은 적지 않는다.
     */
    static final class Tap implements WebSocket.Listener {

        final JsonCodec json;
        final ProviderClient.Reader reader;
        final LinkedBlockingQueue<ProviderEvent> events = new LinkedBlockingQueue<>();
        private final List<RawFrame> raw = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private volatile Integer closeCode;

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
            synchronized (raw) {
                raw.add(new RawFrame(raw.size(), "(binary)", null, List.of()));
            }
            return reader.onBinary(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode = statusCode;
            return reader.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            reader.onError(webSocket, error);
        }

        private void record(String message) {
            String type = "(해석 불가)";
            String responseId = null;
            List<String> keys = List.of();
            try {
                RawInbound inbound = json.read(message, RawInbound.class);
                if (inbound != null && inbound.type() != null) {
                    type = safe(inbound.type());
                    responseId = inbound.responseId();
                }
                Map<?, ?> fields = json.read(message, Map.class);
                if (fields != null) {
                    keys = fields.keySet().stream().map(String::valueOf).map(GptLiveProbe::safe).sorted().toList();
                }
            } catch (RuntimeException e) {
                // 깨진 본문은 type 이름만 '(해석 불가)'로 남긴다(본문은 적지 않는다)
            }
            synchronized (raw) {
                raw.add(new RawFrame(raw.size(), type, responseId, keys));
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
    }

    static String safe(String token) {
        return token != null && SAFE_TOKEN.matcher(token).matches() ? token : "(형식 밖)";
    }

    // ---- 관측 ----

    /** 단계별 관측 개수(원문 없음). */
    static final class Stats {

        boolean started;
        boolean closed;
        int speechStopped;
        int inputSignals;
        int outputAudio;
        long outputAudioBytes;
        int outputTranscript;
        long firstOutputMillis = -1;
        long speechStoppedMillis = -1;
        final Set<String> responseIds = new LinkedHashSet<>();
        final Set<String> doneIds = new LinkedHashSet<>();
        final Map<String, Integer> audioById = new LinkedHashMap<>();
        final Set<String> errorCodes = new TreeSet<>();

        void accept(ProviderEvent event, long elapsedMillis) {
            switch (event) {
                case ProviderEvent.Started started -> this.started = true;
                case ProviderEvent.SpeechStopped stopped -> {
                    speechStopped++;
                    inputSignals++;
                    if (speechStoppedMillis < 0) {
                        speechStoppedMillis = elapsedMillis;
                    }
                }
                case ProviderEvent.InputTranscriptDelta delta -> inputSignals++;
                case ProviderEvent.InputTranscriptDone done -> inputSignals++;
                case ProviderEvent.OutputTranscriptDelta delta -> {
                    outputTranscript++;
                    noteResponse(delta.responseId(), elapsedMillis);
                }
                case ProviderEvent.OutputAudioDelta delta -> {
                    outputAudio++;
                    outputAudioBytes += delta.audio().length;
                    noteResponse(delta.responseId(), elapsedMillis);
                    if (delta.responseId() != null) {
                        audioById.merge(delta.responseId(), 1, Integer::sum);
                    }
                }
                case ProviderEvent.OutputDone done -> {
                    if (done.responseId() != null) {
                        doneIds.add(done.responseId());
                    }
                }
                case ProviderEvent.Error error -> errorCodes.add(safe(error.code()));
                case ProviderEvent.Closed closedEvent -> closed = true;
            }
        }

        private void noteResponse(String responseId, long elapsedMillis) {
            if (firstOutputMillis < 0) {
                firstOutputMillis = elapsedMillis;
            }
            if (responseId != null) {
                responseIds.add(responseId);
            }
        }

        boolean outputSettled() {
            return !responseIds.isEmpty() && doneIds.containsAll(responseIds);
        }
    }

    private Result observe(URI url, String key, InputAudio input, List<Row> rows) {
        Tap tap = new Tap(json);
        Link link;
        long openStart = System.nanoTime();
        try {
            link = opener.open(url, key, tap);
        } catch (Exception e) {
            rows.add(new Row("① 서버 쪽 음성 전송 · 출력 수신", "연결 실패(" + failureName(e) + ")", Verdict.FAIL));
            rows.add(new Row("② instructions.correct + response.create 뒤 응답", "연결 실패로 확인 못 함", Verdict.FAIL));
            return new Result(Outcome.FAIL, rows);
        }
        try {
            return observeOpen(link, tap, input, rows, openStart);
        } finally {
            link.session().close();
            try {
                link.resources().close();
            } catch (Exception e) {
                // 정리 경로: 연결 자원 닫기 실패는 결과에 영향이 없다
            }
        }
    }

    private Result observeOpen(Link link, Tap tap, InputAudio input, List<Row> rows, long openStart) {
        ProviderSession session = link.session();
        Stats phaseA = new Stats();
        long connectedMillis = millisSince(openStart);
        String startFailure = null;
        try {
            session.start(factory.sessionStart(new ChildContext("하늘", 8, List.of("블록"))));
        } catch (ProviderPort.ProviderUnavailableException e) {
            startFailure = e.reason().name();
        }
        long startSent = System.nanoTime();
        await(tap, phaseA, startSent, STARTED_WAIT, () -> phaseA.started || phaseA.closed);
        long startedMillis = millisSince(startSent);
        List<String> startedKeys = tap.rawSnapshot().stream().filter(frame -> frame.type().equals("session.started"))
                .findFirst().map(RawFrame::keys).orElse(List.of());

        // A. 입력 음성(실시간 간격) → 자동 응답 관측
        int sent = 0;
        String sendFailure = null;
        long audioStart = System.nanoTime();
        if (phaseA.started && startFailure == null) {
            for (byte[] frame : input.frames()) {
                try {
                    session.sendInputAudio(factory.inputAudio(frame));
                    sent++;
                } catch (ProviderPort.ProviderUnavailableException e) {
                    sendFailure = e.reason().name();
                    break;
                }
                drainFor(tap, phaseA, audioStart, FRAME_PACE);
                if (phaseA.closed) {
                    sendFailure = "CLOSED";
                    break;
                }
            }
        }
        long audioEnd = System.nanoTime();
        settlePhaseA(tap, phaseA, audioStart);
        boolean autoResponse = !phaseA.responseIds.isEmpty();
        if (autoResponse && !phaseA.outputSettled()) {
            try {
                session.cancelResponse(factory.responseCancel(null));
            } catch (ProviderPort.ProviderUnavailableException e) {
                // 자동 응답 정리 실패는 ②에서 그대로 드러난다
            }
            drainFor(tap, phaseA, audioStart, SETTLE_QUIET);
        }

        // B. 지시 교정 + 응답 생성 → 새 응답
        Stats phaseB = new Stats();
        int rawMarkB = tap.rawCount();
        String instructionFailure = null;
        long instructStart = System.nanoTime();
        if (!phaseA.closed) {
            try {
                session.correctInstructions(factory.instructionsCorrect(DialogInstructions.Correction.INCOMPLETE));
                session.createResponse(factory.responseCreate());
            } catch (ProviderPort.ProviderUnavailableException e) {
                instructionFailure = e.reason().name();
            }
        } else {
            instructionFailure = "CLOSED";
        }
        if (instructionFailure == null) {
            await(tap, phaseB, instructStart, PHASE_B_WAIT, () -> phaseB.closed
                    || phaseB.doneIds.stream().anyMatch(id -> !phaseA.responseIds.contains(id) && phaseB.audioById.containsKey(id)));
        }
        Set<String> newIds = new LinkedHashSet<>(phaseB.responseIds);
        newIds.removeAll(phaseA.responseIds);
        int newAudio = newIds.stream().mapToInt(id -> phaseB.audioById.getOrDefault(id, 0)).sum();
        boolean newDone = newIds.stream().anyMatch(phaseB.doneIds::contains);

        // C. 종료 형식
        Stats phaseC = new Stats();
        int rawMarkC = tap.rawCount();
        long closeStart = System.nanoTime();
        String closeFailure = null;
        if (!phaseA.closed && !phaseB.closed) {
            try {
                link.socket().sendText(json.write(factory.sessionClose()), true)
                        .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                closeFailure = failureName(e);
            }
            await(tap, phaseC, closeStart, CLOSE_WAIT, () -> phaseC.closed);
        }
        long closeMillis = millisSince(closeStart);
        List<RawFrame> raw = tap.rawSnapshot();
        boolean closedMessage = raw.stream().skip(rawMarkC).anyMatch(frame -> frame.type().equals("session.closed"));

        // ① 서버 쪽 음성 전송 · 출력 수신
        int totalOutputAudio = phaseA.outputAudio + phaseB.outputAudio;
        boolean one = phaseA.started && startFailure == null && sendFailure == null && sent == input.frames().size()
                && phaseA.inputSignals > 0 && totalOutputAudio > 0;
        StringBuilder first = new StringBuilder()
                .append("연결 ").append(connectedMillis).append("ms · session.started ")
                .append(phaseA.started ? startedMillis + "ms" : "없음")
                .append(startFailure == null ? "" : "(start 송신 실패 " + startFailure + ")")
                .append(" · 입력 프레임 ").append(sent).append('/').append(input.frames().size()).append(" 송신")
                .append(sendFailure == null ? "" : "(실패 " + sendFailure + ")")
                .append(" · 입력 인식 신호 ").append(phaseA.inputSignals).append("건")
                .append(" · 출력 음성 조각 ").append(totalOutputAudio).append("개(자동 ").append(phaseA.outputAudio)
                .append(" · 지시 뒤 ").append(phaseB.outputAudio).append(", ")
                .append(phaseA.outputAudioBytes + phaseB.outputAudioBytes).append("바이트)");
        if (phaseA.inputSignals == 0 && input.sourceLabel().startsWith("저장소")) {
            first.append(" — 입력이 무음 · 톤 자리 파일이라 인식 신호가 없을 수 있다. env ").append(INPUT_PCM_ENV)
                    .append("로 합성 말소리 파일을 주고 다시 돌린다");
        }
        rows.add(new Row("① 서버 쪽 음성 전송 · 출력 수신", first.toString(), one ? Verdict.PASS : Verdict.FAIL));

        // ② 지시 교정 + 응답 생성 뒤 응답
        boolean two = instructionFailure == null && newAudio > 0 && newDone;
        String second = instructionFailure != null
                ? "correct · create 송신 실패(" + instructionFailure + ")"
                : "correct · create 송신 OK · 새 응답 " + newIds.size() + "개 · 음성 조각 " + newAudio + "개 · 완료 신호 "
                        + (newDone ? "있음" : "없음") + " · 첫 조각까지 "
                        + (phaseB.firstOutputMillis < 0 ? "없음" : phaseB.firstOutputMillis + "ms");
        rows.add(new Row("② instructions.correct + response.create 뒤 응답", second, two ? Verdict.PASS : Verdict.FAIL));

        // ③ 말 끝 신호
        long speechMillis = input.pcmBytes() / 2 * 1000L / 24_000;
        String third = (phaseA.speechStopped == 0 ? "0건" : phaseA.speechStopped + "건 · 음성 송신 시작부터 첫 신호 "
                + phaseA.speechStoppedMillis + "ms") + " (입력 소리 길이 " + speechMillis + "ms · 송신 전체 "
                + millisBetween(audioStart, audioEnd) + "ms)";
        rows.add(new Row("③ speech_stopped", third, phaseA.speechStopped > 0 ? Verdict.PASS : Verdict.NOT_OBSERVED));

        // ④ 종료 형식
        Integer code = tap.closeCode();
        String fourth = (phaseA.closed || phaseB.closed)
                ? "확인 전에 공급자가 먼저 닫음(웹소켓 닫힘 코드 " + (code == null ? "없음" : code) + ")"
                : "session.close 송신" + (closeFailure == null ? "" : " 실패(" + closeFailure + ")") + " → session.closed 메시지 "
                        + (closedMessage ? "있음" : "없음") + " · 웹소켓 닫힘 코드 " + (code == null ? "없음" : code) + " · "
                        + (phaseC.closed ? closeMillis + "ms에 닫힘" : CLOSE_WAIT.toMillis() + "ms 안에 닫힘 없음");
        rows.add(new Row("④ 종료 형식", fourth, phaseC.closed && closeFailure == null ? Verdict.PASS : Verdict.NOT_OBSERVED));

        // ⑤ 응답 생성 확인 사건 · 자동 응답 막기
        rows.add(new Row("⑤-1 응답 생성 확인 사건(첫 조각 전 응답 id)", responseCreatedCandidates(raw, rawMarkB, newIds), Verdict.INFO));
        rows.add(new Row("⑤-2 자동 응답 · 막기 수단", "입력만 보냈을 때(create 없이) 자동 응답 " + phaseA.responseIds.size()
                + "개 · session.started 키 " + startedKeys + " — 막기 수단은 공급자 문서로 사람이 확인", Verdict.INFO));

        // 그 밖: 앱이 모르는 type · 오류 code
        Map<String, Integer> unknown = new TreeMap<>();
        raw.stream().filter(frame -> !KNOWN_TYPES.contains(frame.type()))
                .forEach(frame -> unknown.merge(frame.type(), 1, Integer::sum));
        Set<String> errors = new TreeSet<>(phaseA.errorCodes);
        errors.addAll(phaseB.errorCodes);
        errors.addAll(phaseC.errorCodes);
        rows.add(new Row("참고: 앱이 모르는 type · session.error", "모르는 type " + (unknown.isEmpty() ? "없음" : unknown)
                + " · session.error code " + (errors.isEmpty() ? "없음" : errors), Verdict.INFO));

        return new Result(one && two ? Outcome.PASS : Outcome.FAIL, rows);
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
            if (!KNOWN_TYPES.contains(frame.type())) {
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
            ProviderEvent event = poll(tap, POLL);
            long now = System.nanoTime();
            if (event != null) {
                stats.accept(event, millisBetween(phaseStart, now));
                lastEvent = now;
                continue;
            }
            long quiet = now - lastEvent;
            if (stats.outputSettled() && quiet >= SETTLE_QUIET.toNanos()) {
                return;
            }
            if (stats.responseIds.isEmpty() && quiet >= NO_OUTPUT_QUIET.toNanos()) {
                return;
            }
        }
    }

    private static void await(Tap tap, Stats stats, long phaseStart, Duration limit, java.util.function.BooleanSupplier done) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            ProviderEvent event = poll(tap, POLL);
            if (event != null) {
                stats.accept(event, millisSince(phaseStart));
            }
        }
    }

    private static void drainFor(Tap tap, Stats stats, long phaseStart, Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            ProviderEvent event = poll(tap, Duration.ofNanos(remaining));
            if (event != null) {
                stats.accept(event, millisSince(phaseStart));
            }
        }
    }

    private static ProviderEvent poll(Tap tap, Duration wait) {
        try {
            return tap.events.poll(wait.toNanos(), TimeUnit.NANOSECONDS);
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
    private static String failureName(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause instanceof java.util.concurrent.ExecutionException) {
            cause = cause.getCause();
        }
        if (cause instanceof ProviderPort.ProviderUnavailableException unavailable) {
            return unavailable.reason().name();
        }
        return cause.getClass().getSimpleName();
    }

    // ---- 보고서 ----

    private void writeReport(Result result, InputAudio input, String model) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# GPT-Live 격리 실호출 보고서 (U11 W7 · FR9.1 · BR9.2)\n\n");
        out.append("outcome=").append(result.outcome()).append(" evidence=")
                .append(result.outcome() == Outcome.SKIPPED_NO_KEY ? "NONE" : "REAL_API_SYNTHETIC").append("\n\n");
        out.append("- 실행 시각(UTC): ").append(new SystemMujoongClock().now()).append('\n');
        if (result.outcome() == Outcome.SKIPPED_NO_KEY) {
            out.append("- 증거 종류: 없음(실호출 안 함). SKIPPED_NO_KEY는 통과로 보지 않는다(BR9.1).\n");
        } else {
            out.append("- 증거 종류: [실호출-합성] 실제 API · 합성 음성(R-M11). 테스트 대역 · 실제 기기 증거가 아니다.\n");
            out.append("- 모델(코드 상수): ").append(model).append('\n');
        }
        if (input != null) {
            out.append("- 입력 음성: ").append(input.sourceLabel()).append(" · ")
                    .append(input.pcmBytes() / 2 * 1000L / 24_000).append("ms · 프레임 ").append(input.frames().size())
                    .append("개(뒤 무음 ").append(TRAILING_SILENCE_FRAMES).append("개 포함)\n");
        }
        out.append("- 계속 · 중단 기준: ① · ②가 PASS여야 계속한다. 하나라도 FAIL이면 U11 구현을 멈추고 ADR-007을 다시 정한다(BR9.2).\n");
        out.append("- 이 보고서에는 전사 원문 · 음성 · 키 · 지시문 원문 · 주소를 담지 않는다(R-F3 · R-F14).\n\n");
        out.append("| 항목 | 관측 | 통과 여부 |\n|---|---|---|\n");
        for (Row row : result.rows()) {
            out.append("| ").append(cell(row.item())).append(" | ").append(cell(row.observation())).append(" | ")
                    .append(row.verdict()).append(" |\n");
        }
        if (report.getParent() != null) {
            Files.createDirectories(report.getParent());
        }
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
    }

    private static String cell(String value) {
        return value.replace("|", "/").replace("\n", " ");
    }

    Path report() {
        return report;
    }

    /** 표 행을 사람이 읽는 한 줄 요약으로(로그용, 원문 없음). */
    static String summary(Result result) {
        return "GPT-Live 실호출 " + result.outcome() + " — "
                + String.join(" · ", result.rows().stream().map(row -> row.item().split(" ")[0] + "=" + row.verdict()).toList());
    }

    static boolean anyVerdict(Result result, Verdict verdict) {
        return result.rows().stream().anyMatch(row -> row.verdict() == verdict);
    }
}
