package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
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
 * 격리 실호출 도구의 추가 전용 실험 흐름(P1 ~ P4, P6)을 가짜 공급자 루프백으로 끝까지 돌려 보고서 계산이 맞는지 본다 (U11 FR9.1 · FR12.7 · W7). 실제
 * 공급자 · 실제 키를 쓰지 않는다(R-F1 · R-F3): 키는 실행 때 만든 무작위 문자열이고 연결은 루프백이다. 대본이 실호출에서 관측된 모양을 흉내 낸다 —
 * 지시 추가 확인 사건(start_ms · end_ms · client_event_id), 끼어들기 때 이어지는 낡은 꼬리, 표식 낱말 응답, 음성보다 앞서거나 늦게 도착하는 전사, 지시가
 * 유지되는 평범한 발화, 한 번뿐 지시. 이 시험은 도구의 보고서 형식 · 관측 계산을 확인할 뿐 실호출 성공을 뜻하지 않는다(R-M11: 테스트 대역 증거).
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
    void aMarkerObeyingProviderFillsTheTimelineRulesLagPersistenceAndOneShotRowsWithoutLeaks(@TempDir Path dir) throws Exception {
        String key = TestSecrets.base64Url(24);
        try (FakeProviderWsServer server = FakeProviderWsServer.start()) {
            GptLiveProbe.Result result = runProbe(dir, server, key, new Script(server, true, 2, true), Map.of());

            assertThat(result.outcome()).isEqualTo(GptLiveProbe.Outcome.PASS);
            List<GptLiveProbe.Row> rows = result.rows();
            assertThat(rows).hasSize(GptLiveProbe.ITEMS.size());
            assertThat(rows.get(0).verdict()).as("① 표본율 수락").isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(rows.get(1).observation()).as("② 사건 이름").contains("session.output_transcript.delta", "입력 전사 완료 사건 있음");
            assertThat(rows.get(2).observation()).as("P1").contains("첫 출력 조각", "조각 사이 최대 쉼");

            assertThat(rows.get(3).observation()).as("P2 시간표").contains("확인 사건: 도착 +", "start_ms=", "표식 전사: 도착 +", "표식 앞 전사 0자",
                    "확인 도착~표식 전사 도착 사이 조각 2개", "조각 위치(0부터, 전체", "경계를 알리는 모르는 type 없음");
            assertThat(rows.get(4).verdict()).isEqualTo(GptLiveProbe.Verdict.INFO);
            assertThat(rows.get(4).observation()).as("P2 규칙: 확인이 낡은 조각 하나 앞에 도착하므로 R1은 하나 샌다")
                    .containsPattern("R1\\(확인 도착 뒤 조각 = 새 응답\\): 낡은 조각 통과 1개 · 새 조각 잘못 버림 0개")
                    .containsPattern("R2\\(시계 ≥ 확인 end_ms \\d+ = 새 응답\\): 낡은 조각 통과 0개 · 새 조각 잘못 버림 0개")
                    .contains("확인 end_ms − 표식 start_ms = 0ms");

            assertThat(rows.get(5).observation()).as("P3: 전사가 창이 닫힌 뒤 늦게 도착해도 start_ms로 귀속")
                    .contains("말이 저절로 시작 예(", "P3 표식 start_ms 귀속 예(표식 앞 전사 0자 · 표식으로 시작) / 도착 창 아니오(",
                            "늦게 도착한 P3 전사 조각 1개");
            assertThat(rows.get(6).observation()).as("P4").contains("평범한 발화 1회 뒤", "평범한 발화 2회 뒤",
                    "P3 표식 유지: 1회 뒤 예 / 2회 뒤 예");
            assertThat(rows.get(7).observation()).as("P6").contains("지시 직후 말이 저절로 시작 예(", "한 번뿐 지시가 지켜졌다");
            assertThat(rows.get(8).observation()).as("전사 지연").contains("P1 n=", "양수 = 전사가 음성보다 앞", "start_ms 도착 순서 단조 예");
            assertThat(rows.get(GptLiveProbe.ACCEPT_ROW).verdict()).isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(rows.get(GptLiveProbe.ACCEPT_ROW).observation()).contains("지시 추가 3번 송신", "일치 3/3", "확인 사건 3건",
                    "client_event_id:string", "start_ms:number", "end_ms:number");
            assertThat(rows.get(10).verdict()).isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(rows.get(11).verdict()).as("종료 형식").isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(rows.get(18).observation()).contains("session.usage.updated");

            assertThat(server.received("session.instructions.append")).hasSize(3).allSatisfy(append -> {
                assertThat(append.has("delegation_id")).as("delegation_id는 null이어도 있어야 한다").isTrue();
                assertThat(append.get("delegation_id").isNull()).isTrue();
            });
            assertThat(server.received("session.instructions.append").stream().map(node -> node.get("content").asString()).toList())
                    .containsExactly(GptLiveProbe.INSTRUCTION_P2, GptLiveProbe.INSTRUCTION_P3, GptLiveProbe.INSTRUCTION_P6);
            assertThat(server.received("response.create")).as("response.create는 보내지 않는다").isEmpty();
            assertThat(server.received("session.update")).isEmpty();
            assertThat(server.received().stream().map(node -> node.get("type").asString()))
                    .allMatch(FakeProviderWsServer.SUPPORTED_TYPES::contains);

            String written = Files.readString(dir.resolve("gpt-live/report.md"));
            assertThat(written).contains("outcome=PASS evidence=REAL_API_SYNTHETIC", "rate=24000 phases=P1,P2,P3,P4,P6")
                    .doesNotContain(key, SECRET_TEXT, "Bearer", "127.0.0.1");
        }
    }

    @Test
    void aProviderThatIgnoresTheInstructionsIsReportedAsNoMarkerAndJudgmentImpossible(@TempDir Path dir) throws Exception {
        String key = TestSecrets.base64Url(24);
        try (FakeProviderWsServer server = FakeProviderWsServer.start()) {
            GptLiveProbe.Result result = runProbe(dir, server, key, new Script(server, false, 1, false),
                    Map.of(GptLiveProbe.PLAIN_TURNS_ENV, "1", GptLiveProbe.ONE_SHOT_ENV, "0"));

            assertThat(result.outcome()).as("지시를 받아들였으면(오류 0건) PASS").isEqualTo(GptLiveProbe.Outcome.PASS);
            List<GptLiveProbe.Row> rows = result.rows();
            assertThat(rows.get(3).observation()).contains("표식 응답 시작 아니오(");
            assertThat(rows.get(4).verdict()).isEqualTo(GptLiveProbe.Verdict.NOT_OBSERVED);
            assertThat(rows.get(5).observation()).contains("말이 저절로 시작 아니오(조각 0개)", "P3 표식 start_ms 귀속 아니오(");
            assertThat(rows.get(6).observation()).contains("평범한 발화 1회 뒤", "P3에서 표식을 못 봐서 유지 여부는 판단 불가")
                    .doesNotContain("평범한 발화 2회 뒤");
            assertThat(rows.get(7).observation()).startsWith("시험 안 함");
            assertThat(rows.get(GptLiveProbe.ACCEPT_ROW).verdict()).isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(server.received("session.instructions.append")).hasSize(2);
            assertThat(Files.readString(dir.resolve("gpt-live/report.md"))).contains("rate=24000 phases=P1,P2,P3,P4\n")
                    .doesNotContain(key, SECRET_TEXT);
        }
    }

    @Test
    void anErrorAfterTheAppendFailsTheAcceptanceRowAndTheOutcome(@TempDir Path dir) throws Exception {
        String key = TestSecrets.base64Url(24);
        try (FakeProviderWsServer server = FakeProviderWsServer.start()) {
            Script script = new Script(server, false, 1, false);
            script.rejectAppends = true;

            GptLiveProbe.Result result = runProbe(dir, server, key, script,
                    Map.of(GptLiveProbe.PLAIN_TURNS_ENV, "1", GptLiveProbe.ONE_SHOT_ENV, "0"));

            assertThat(result.outcome()).isEqualTo(GptLiveProbe.Outcome.FAIL);
            assertThat(result.rows().get(GptLiveProbe.ACCEPT_ROW).verdict()).isEqualTo(GptLiveProbe.Verdict.FAIL);
            assertThat(result.rows().get(GptLiveProbe.ACCEPT_ROW).observation()).contains("missing_required_parameter", "param=delegation_id");
            assertThat(result.rows().get(12).verdict()).as("오류 사건").isEqualTo(GptLiveProbe.Verdict.FAIL);
        }
    }

    private static GptLiveProbe.Result runProbe(Path dir, FakeProviderWsServer server, String key, Script script, Map<String, String> extraEnv)
            throws Exception {
        Path pcm = dir.resolve("synthetic.pcm");
        Files.write(pcm, new byte[GptLiveProbe.FRAME_BYTES * 10]);
        Map<String, String> env = new HashMap<>(Map.of(ProviderClient.API_KEY, key, GptLiveProbe.INPUT_PCM_ENV, pcm.toString()));
        env.putAll(extraEnv);
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
     * 가짜 공급자 대본. 자기 오디오 시계(조각 하나 960바이트 = 20ms)를 세어 전사 {@code start_ms}를 그 시계로 낸다. 끼어들기(P2)에서는 지시가 오면 낡은
     * 조각 하나가 더 나가기 전에 확인 사건(end_ms = 낡은 꼬리의 끝)을 먼저 내보내 R1이 낡은 조각을 하나 새게 만들고, 표식 전사는 새 응답 첫 조각 뒤에
     * 늦게 보낸다. 유휴 추가(P3)의 전사는 조용한 시간이 지난 뒤에야 도착한다(창이 닫힌 뒤 늦은 전사).
     */
    private static final class Script {

        final FakeProviderWsServer server;
        final AtomicBoolean stop = new AtomicBoolean();
        final boolean markers;
        final int plainTurns;
        final boolean oneShot;
        boolean rejectAppends;
        private long clock;
        private int appendsAcked;

        Script(FakeProviderWsServer server, boolean markers, int plainTurns, boolean oneShot) {
            this.server = server;
            this.markers = markers;
            this.plainTurns = plainTurns;
            this.oneShot = oneShot;
        }

        void play() {
            try {
                // P1: 전사가 음성보다 먼저 도착
                waitFor(() -> frames() >= PER_UTTERANCE);
                inputTranscripts(1);
                piece("합성응답" + SECRET_TEXT, clock, clock + 60);
                audio(3);
                server.emitUsage(3.0);
                // P2: 낡은 꼬리가 이어지는 동안 지시 #1이 온다
                waitFor(() -> frames() >= PER_UTTERANCE * 2);
                inputTranscripts(2);
                piece("낡은응답" + SECRET_TEXT, clock, clock + 100);
                for (int i = 0; i < 25 && !stop.get() && appends() < 1; i++) {
                    audio1();
                    Thread.sleep(40);
                }
                waitFor(() -> appends() >= 1);
                audio1(); // 낡은 조각 하나
                long cut = clock + 20;
                ackNew(clock, cut); // 확인이 낡은 조각 하나(아래) 앞에 도착하고 end_ms는 그 조각의 끝
                audio1(); // 낡은 조각 하나 더(확인 뒤에 도착)
                if (markers) {
                    audio1(); // 새 응답의 첫 조각(시계가 cut에서 시작)
                    piece(GptLiveProbe.MARKER_P2 + " " + SECRET_TEXT, cut, cut + 100); // 전사는 첫 조각 뒤에 도착
                    audio(2);
                }
                // P3: 출력이 없을 때 지시 #2 — 말이 저절로 시작하고 전사는 창이 닫힌 뒤에 도착
                waitFor(() -> appends() >= 2);
                ackNew(clock, clock);
                if (markers) {
                    long start = clock;
                    audio(4);
                    Thread.sleep(450);
                    piece(GptLiveProbe.MARKER_P3 + " " + SECRET_TEXT, start, start + 80);
                }
                // P4: 평범한 발화(지시가 유지되면 표식으로 시작)
                for (int turn = 1; turn <= plainTurns; turn++) {
                    int needed = PER_UTTERANCE * (2 + turn);
                    waitFor(() -> frames() >= needed);
                    plainResponse();
                }
                // P6: 한 번뿐 지시
                if (oneShot) {
                    waitFor(() -> appends() >= 3);
                    ackNew(clock, clock);
                    if (markers) {
                        piece(GptLiveProbe.MARKER_P6 + " " + SECRET_TEXT, clock, clock + 60);
                        audio(3);
                    }
                    int needed = PER_UTTERANCE * (3 + plainTurns);
                    waitFor(() -> frames() >= needed);
                    plainResponse();
                }
                waitFor(() -> !server.received("session.close").isEmpty());
                server.emit(Map.of("type", "session.closed", "reason", "client"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IllegalStateException e) {
                // 아직 연결이 없거나 이미 닫혔다
            }
        }

        private void plainResponse() throws InterruptedException {
            inputTranscripts(0);
            piece((markers ? GptLiveProbe.MARKER_P3 + " " : "") + SECRET_TEXT, clock, clock + 60);
            audio(2);
        }

        private int frames() {
            return server.received("session.input_audio.append").size();
        }

        private int appends() {
            return server.received("session.instructions.append").size();
        }

        private void ackNew(long startMs, long endMs) {
            List<?> all = server.received("session.instructions.append");
            for (int n = appendsAcked; n < all.size(); n++) {
                String id = server.received("session.instructions.append").get(n).get("event_id").asString();
                if (rejectAppends) {
                    server.emit(Map.of("type", "error", "error", Map.of("type", "invalid_request_error", "code",
                            "missing_required_parameter", "param", "delegation_id", "message", "Missing required parameter")));
                } else {
                    server.emit(Map.of("type", "session.instructions.appended", "event_id", "srv-" + (n + 1), "client_event_id", id,
                            "start_ms", startMs, "end_ms", endMs));
                }
            }
            appendsAcked = all.size();
        }

        private void inputTranscripts(int n) {
            server.emit(new FakeProviderWsServer.Event("session.input_transcript.delta", "i" + n, "합성", null));
            server.emit(new FakeProviderWsServer.Event("session.input_transcript.done", "i" + n, null, "합성"));
        }

        private void piece(String text, long startMs, long endMs) {
            server.emit(Map.of("type", "session.output_transcript.delta", "delta", text, "start_ms", startMs, "end_ms", endMs,
                    "event_id", "t-" + startMs));
        }

        private void audio1() {
            server.emitAudio(new byte[960]);
            clock += 20;
        }

        private void audio(int chunks) throws InterruptedException {
            for (int i = 0; i < chunks; i++) {
                audio1();
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
