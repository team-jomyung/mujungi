package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import kr.mujoong.testsupport.provider.FakeProviderWsServer;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 격리 실호출 도구의 추가 전용 실험 흐름(P1 ~ P5)을 가짜 공급자 루프백으로 끝까지 돌려 보고서 항목이 채워지는지 본다 (U11 FR9.1 · FR12.7 · W7). 실제
 * 공급자 · 실제 키를 쓰지 않는다(R-F1 · R-F3): 키는 실행 때 만든 무작위 문자열이고 연결은 루프백이다. 대본이 실호출에서 관측된 모양(지시 추가 확인
 * 사건 · 끼어들기 · 표식 낱말 응답)을 흉내 낸다. 이 시험은 도구의 보고서 형식 · 관측 계산을 확인할 뿐 실호출 성공을 뜻하지 않는다(R-M11: 테스트 대역
 * 증거).
 */
@Tag("u11")
class GptLiveLoopbackTest {

    private static final GptLiveProbe.Timing FAST = new GptLiveProbe.Timing(Duration.ofMillis(1), Duration.ofSeconds(3),
            Duration.ofMillis(200), Duration.ofMillis(400), Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMillis(5), 4,
            Duration.ofMillis(30));
    private static final String SECRET_TEXT = "합성응답문장비밀표식";
    /** 입력 10프레임 + 뒤 무음 4프레임. */
    private static final int PER_UTTERANCE = 14;

    @Test
    void aMarkerObeyingLoopbackProviderFillsEveryPhaseWithoutLeakingTextAudioOrKeys(@TempDir Path dir) throws Exception {
        String key = TestSecrets.base64Url(24);
        try (FakeProviderWsServer server = FakeProviderWsServer.start()) {
            GptLiveProbe.Result result = runProbe(dir, server, key, new Script(server, true, false), null);

            assertThat(result.outcome()).isEqualTo(GptLiveProbe.Outcome.PASS);
            List<GptLiveProbe.Row> rows = result.rows();
            assertThat(rows).hasSize(GptLiveProbe.ITEMS.size());
            assertThat(rows.get(0).verdict()).as("① 표본율 수락").isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(rows.get(1).verdict()).as("② 사건 이름").isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(rows.get(1).observation()).contains("session.output_transcript.delta", "입력 전사 완료 사건 있음");
            assertThat(rows.get(2).verdict()).as("P1").isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(rows.get(2).observation()).contains("첫 출력 조각", "조각 사이 최대 쉼");
            assertThat(rows.get(3).observation()).as("P2").contains("표식 낱말 응답 시작 예(", "표식 앞 전사 0자", "낡은 꼬리 추정",
                    "경계를 알리는 모르는 type 없음");
            assertThat(rows.get(4).observation()).as("P3").contains("말이 저절로 시작 예(", "표식 낱말 예(");
            assertThat(rows.get(5).observation()).as("P4").contains("지시가 다음 응답에도 남는다");
            assertThat(rows.get(6).observation()).as("P5").startsWith("시험 안 함");
            assertThat(rows.get(7).verdict()).as("지시 추가 수락").isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(rows.get(7).observation()).contains("지시 추가 2번 송신", "일치 2/2", "확인 사건 2건",
                    "client_event_id:string");
            assertThat(rows.get(8).verdict()).isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(rows.get(9).verdict()).as("종료 형식").isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(rows.get(16).observation()).contains("session.usage.updated");

            assertThat(server.received("session.start")).singleElement().satisfies(start ->
                    assertThat(start.get("session").get("audio").get("format").get("rate").asInt()).isEqualTo(24_000));
            assertThat(server.received("session.instructions.append")).hasSize(2).allSatisfy(append -> {
                assertThat(append.has("delegation_id")).as("delegation_id는 null이어도 있어야 한다").isTrue();
                assertThat(append.get("delegation_id").isNull()).isTrue();
                assertThat(append.get("content").asString()).isNotBlank();
            });
            assertThat(server.received("session.instructions.append").get(0).get("content").asString())
                    .isEqualTo(GptLiveProbe.INSTRUCTION_P2);
            assertThat(server.received("session.instructions.append").get(1).get("content").asString())
                    .isEqualTo(GptLiveProbe.INSTRUCTION_P3);
            assertThat(server.received("response.create")).as("response.create는 보내지 않는다").isEmpty();
            assertThat(server.received().stream().map(node -> node.get("type").asString()))
                    .allMatch(FakeProviderWsServer.SUPPORTED_TYPES::contains);

            String written = Files.readString(dir.resolve("gpt-live/report.md"));
            assertThat(written).contains("outcome=PASS evidence=REAL_API_SYNTHETIC", "rate=24000 phases=P1,P2,P3,P4")
                    .doesNotContain(key, SECRET_TEXT, "Bearer", "127.0.0.1");
        }
    }

    @Test
    void aProviderThatIgnoresTheInstructionsAndTheUpdateIsReportedAsNoMarkerAndPossiblyAutoResponseOff(@TempDir Path dir)
            throws Exception {
        String key = TestSecrets.base64Url(24);
        try (FakeProviderWsServer server = FakeProviderWsServer.start()) {
            GptLiveProbe.Result result = runProbe(dir, server, key, new Script(server, false, true),
                    "{\"type\":\"session.update\",\"session\":{}}");

            assertThat(result.outcome()).as("지시를 받아들였으면(오류 0건) PASS").isEqualTo(GptLiveProbe.Outcome.PASS);
            List<GptLiveProbe.Row> rows = result.rows();
            assertThat(rows.get(3).observation()).contains("표식 낱말 응답 시작 아니오(");
            assertThat(rows.get(4).observation()).contains("말이 저절로 시작 아니오(조각 0개)", "표식 낱말 아니오(");
            assertThat(rows.get(5).observation()).contains("지시가 남지 않았다");
            assertThat(rows.get(6).observation()).contains("조각 0개", "자동 응답이 꺼졌을 수 있다");
            assertThat(rows.get(7).verdict()).isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(server.received("session.update")).hasSize(1);
            assertThat(server.received("response.create")).isEmpty();
            assertThat(Files.readString(dir.resolve("gpt-live/report.md"))).contains("rate=24000 phases=P1,P2,P3,P4,P5")
                    .doesNotContain(key, SECRET_TEXT);
        }
    }

    @Test
    void anErrorAfterTheAppendFailsTheAcceptanceRowAndTheOutcome(@TempDir Path dir) throws Exception {
        String key = TestSecrets.base64Url(24);
        try (FakeProviderWsServer server = FakeProviderWsServer.start()) {
            Script script = new Script(server, false, false);
            script.rejectAppends = true;

            GptLiveProbe.Result result = runProbe(dir, server, key, script, null);

            assertThat(result.outcome()).isEqualTo(GptLiveProbe.Outcome.FAIL);
            assertThat(result.rows().get(7).verdict()).isEqualTo(GptLiveProbe.Verdict.FAIL);
            assertThat(result.rows().get(7).observation()).contains("missing_required_parameter", "param=delegation_id");
            assertThat(result.rows().get(10).verdict()).as("오류 사건").isEqualTo(GptLiveProbe.Verdict.FAIL);
        }
    }

    private static GptLiveProbe.Result runProbe(Path dir, FakeProviderWsServer server, String key, Script script, String updateJson)
            throws Exception {
        Path pcm = dir.resolve("synthetic.pcm");
        Files.write(pcm, new byte[GptLiveProbe.FRAME_BYTES * 10]);
        Map<String, String> env = new java.util.HashMap<>(Map.of(ProviderClient.API_KEY, key, GptLiveProbe.INPUT_PCM_ENV, pcm.toString()));
        if (updateJson != null) {
            env.put(GptLiveProbe.UPDATE_JSON_ENV, updateJson);
        }
        AtomicBoolean stop = script.stop;
        Thread thread = Thread.ofVirtual().start(script::play);
        GptLiveProbe.Opener opener = (url, apiKey, tap) -> GptLiveProbe.openLikeProviderClient(server.uri(), apiKey, tap);
        Path report = dir.resolve("gpt-live/report.md");
        GptLiveProbe probe = new GptLiveProbe(env::get, Path.of("..").toAbsolutePath().normalize(), report, opener, FAST);
        try {
            return probe.run();
        } finally {
            stop.set(true);
            thread.join(2_000);
        }
    }

    /**
     * 가짜 공급자 대본: 들어온 요청을 보고 실호출에서 관측된 모양의 사건을 낸다. 지시 추가에는 확인 사건({@code session.instructions.appended},
     * {@code client_event_id})을 돌려주고, {@code markers}면 지시를 따라 표식 낱말로 시작하는 응답을 낸다(끼어들기 때 낡은 꼬리는 지시 직전까지 이어진다).
     */
    private static final class Script {

        final FakeProviderWsServer server;
        final AtomicBoolean stop = new AtomicBoolean();
        final boolean markers;
        final boolean updateMode;
        boolean rejectAppends;
        private int appendsAcked;

        Script(FakeProviderWsServer server, boolean markers, boolean updateMode) {
            this.server = server;
            this.markers = markers;
            this.updateMode = updateMode;
        }

        void play() {
            try {
                // P1: 첫 발화에 자동 응답
                waitFor(() -> frames() >= PER_UTTERANCE);
                emitTranscripts(1);
                respond("합성응답", 2);
                server.emitUsage(3.0);
                // P2: 둘째 발화에 낡은 응답이 이어지는 동안 지시 #1이 온다
                waitFor(() -> frames() >= PER_UTTERANCE * 2);
                emitTranscripts(2);
                server.emit(transcript("합성"));
                for (int i = 0; i < 12 && !stop.get() && appends() < 1; i++) {
                    server.emitAudio(new byte[960]);
                    Thread.sleep(40);
                }
                waitFor(() -> appends() >= 1);
                ackNewAppends();
                if (markers) {
                    server.emit(transcript(GptLiveProbe.MARKER_P2 + " " + SECRET_TEXT));
                    respondAudio(2);
                }
                // P3: 출력이 없을 때 지시 #2
                waitFor(() -> appends() >= 2);
                ackNewAppends();
                if (markers) {
                    server.emit(transcript(GptLiveProbe.MARKER_P3 + " " + SECRET_TEXT));
                    respondAudio(2);
                }
                // P4: 평범한 발화(지시가 남았으면 표식으로 시작)
                waitFor(() -> frames() >= PER_UTTERANCE * 3);
                emitTranscripts(3);
                server.emit(transcript((markers ? GptLiveProbe.MARKER_P3 + " " : "") + SECRET_TEXT));
                respondAudio(2);
                // P5: 자동 응답이 꺼졌다면 응답이 없다
                if (updateMode) {
                    waitFor(() -> frames() >= PER_UTTERANCE * 4);
                }
                waitFor(() -> !server.received("session.close").isEmpty());
                server.emit(Map.of("type", "session.closed", "reason", "client"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IllegalStateException e) {
                // 아직 연결이 없거나 이미 닫혔다
            }
        }

        private int frames() {
            return server.received("session.input_audio.append").size();
        }

        private int appends() {
            return server.received("session.instructions.append").size();
        }

        private void ackNewAppends() {
            List<?> all = server.received("session.instructions.append");
            for (int n = appendsAcked; n < all.size(); n++) {
                String id = server.received("session.instructions.append").get(n).get("event_id").asString();
                if (rejectAppends) {
                    server.emit(Map.of("type", "error", "error", Map.of("type", "invalid_request_error", "code",
                            "missing_required_parameter", "param", "delegation_id", "message", "Missing required parameter")));
                } else {
                    server.emit(Map.of("type", "session.instructions.appended", "event_id", "srv-" + (n + 1), "client_event_id", id));
                }
            }
            appendsAcked = all.size();
        }

        private void emitTranscripts(int n) {
            server.emit(new FakeProviderWsServer.Event("session.input_transcript.delta", "i" + n, "합성", null));
            server.emit(new FakeProviderWsServer.Event("session.input_transcript.done", "i" + n, null, "합성"));
        }

        private static Map<String, Object> transcript(String text) {
            return Map.of("type", "session.output_transcript.delta", "delta", text, "start_ms", 0, "end_ms", 100, "event_id", "t-" + text.length());
        }

        private void respond(String prefix, int chunks) throws InterruptedException {
            server.emit(transcript(prefix + SECRET_TEXT));
            respondAudio(chunks);
        }

        private void respondAudio(int chunks) throws InterruptedException {
            for (int i = 0; i < chunks; i++) {
                server.emitAudio(new byte[960]);
                Thread.sleep(30);
            }
        }

        private void waitFor(BooleanSupplier condition) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (!stop.get() && !condition.getAsBoolean() && System.nanoTime() < deadline) {
                Thread.sleep(3);
            }
        }
    }
}
