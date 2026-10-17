package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.mujoong.testsupport.provider.FakeProviderWsServer;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 격리 실호출 도구의 흐름을 가짜 공급자 루프백으로 끝까지 돌려 보고서 항목이 채워지는지 본다 (U11 FR9.1 · FR12.7 · W7). 실제 공급자 · 실제 키를
 * 쓰지 않는다(R-F1 · R-F3): 키는 실행 때 만든 무작위 문자열이고 연결은 루프백이다. 이 시험은 도구의 보고서 형식 · 관측 계산을 확인할 뿐
 * 실호출 성공을 뜻하지 않는다(R-M11: 테스트 대역 증거).
 */
@Tag("u11")
class GptLiveLoopbackTest {

    private static final GptLiveProbe.Timing FAST = new GptLiveProbe.Timing(Duration.ofMillis(1), Duration.ofSeconds(3),
            Duration.ofMillis(200), Duration.ofMillis(400), Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMillis(5), 4);
    private static final String SECRET_TEXT = "합성응답문장비밀표식";

    @Test
    void aScriptedLoopbackProviderFillsEveryReportItemWithoutLeakingTextAudioOrKeys(@TempDir Path dir) throws Exception {
        Path pcm = dir.resolve("synthetic.pcm");
        Files.write(pcm, new byte[GptLiveProbe.FRAME_BYTES * 10]);
        String key = TestSecrets.base64Url(24);
        Map<String, String> env = Map.of(ProviderClient.API_KEY, key, GptLiveProbe.INPUT_PCM_ENV, pcm.toString(),
                GptLiveProbe.UTTERANCES_ENV, "2");
        try (FakeProviderWsServer server = FakeProviderWsServer.start()) {
            AtomicBoolean stop = new AtomicBoolean();
            Thread script = Thread.ofVirtual().start(() -> play(server, stop));
            GptLiveProbe.Opener opener = (url, apiKey, tap) -> GptLiveProbe.openLikeProviderClient(server.uri(), apiKey, tap);
            Path report = dir.resolve("gpt-live/report.md");
            GptLiveProbe probe = new GptLiveProbe(env::get, Path.of("..").toAbsolutePath().normalize(), report, opener, FAST);

            GptLiveProbe.Result result;
            try {
                result = probe.run();
            } finally {
                stop.set(true);
                script.join(2_000);
            }

            assertThat(result.outcome()).isEqualTo(GptLiveProbe.Outcome.PASS);
            assertThat(result.rows()).hasSize(GptLiveProbe.ITEMS.size());
            List<GptLiveProbe.Row> rows = result.rows();
            assertThat(rows.get(0).verdict()).as("① 표본율 수락").isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(rows.get(1).verdict()).as("② 출력 전사 사건").isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(rows.get(2).verdict()).as("③ 입력 전사").isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(rows.get(3).verdict()).as("④ 말 끝").isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(rows.get(4).verdict()).as("⑤ 응답 경계 사건 없음").isEqualTo(GptLiveProbe.Verdict.NOT_OBSERVED);
            assertThat(rows.get(5).verdict()).as("⑥ 지시문 길이 사건 두 건").isEqualTo(GptLiveProbe.Verdict.INFO);
            assertThat(rows.get(6).verdict()).as("⑦ append + create 뒤 응답").isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(rows.get(7).verdict()).as("⑧ 자동 응답 시간").isEqualTo(GptLiveProbe.Verdict.INFO);
            assertThat(rows.get(7).observation()).contains("P95", "제안 auto-response-start");
            assertThat(rows.get(8).observation()).contains("최대 쉼").contains("session.update 시험 안 함");
            assertThat(rows.get(9).verdict()).as("종료 형식").isEqualTo(GptLiveProbe.Verdict.PASS);
            assertThat(rows.get(16).observation()).contains("session.usage.updated");
            assertThat(server.received("session.start")).singleElement().satisfies(start ->
                    assertThat(start.get("session").get("audio").get("format").get("rate").asInt()).isEqualTo(24_000));
            assertThat(server.received("session.instructions.append")).hasSize(2);
            assertThat(server.received("response.create")).hasSize(1);
            assertThat(server.received().stream().map(node -> node.get("type").asString()))
                    .allMatch(FakeProviderWsServer.SUPPORTED_TYPES::contains);

            String written = Files.readString(report);
            assertThat(written).contains("outcome=PASS evidence=REAL_API_SYNTHETIC", "rate=24000 utterances=2")
                    .doesNotContain(key, SECRET_TEXT, "Bearer", "127.0.0.1");
        }
    }

    /** 가짜 공급자 대본: 들어온 요청을 보고 실호출에서 관측된 모양의 사건을 낸다(사건 이름이 미확인인 전사 · 말 끝은 임시 이름). */
    private static void play(FakeProviderWsServer server, AtomicBoolean stop) {
        int utterancesAnswered = 0;
        int appendsSeen = 0;
        boolean created = false;
        boolean closed = false;
        int perUtterance = 10 + 4;
        while (!stop.get() && !closed) {
            try {
                int frames = server.received("session.input_audio.append").size();
                if (utterancesAnswered < 2 && frames >= perUtterance * (utterancesAnswered + 1)) {
                    utterancesAnswered++;
                    server.emit(new FakeProviderWsServer.Event("session.input_audio.speech_stopped", null, null, null));
                    server.emit(new FakeProviderWsServer.Event("session.input_transcript.delta", "i" + utterancesAnswered, "합성", null));
                    server.emit(new FakeProviderWsServer.Event("session.input_transcript.done", "i" + utterancesAnswered, null, "합성"));
                    server.emit(new FakeProviderWsServer.Event("session.output_transcript.delta", null, SECRET_TEXT, null));
                    server.emitAudio(new byte[960]);
                    Thread.sleep(30);
                    server.emitAudio(new byte[960]);
                    if (utterancesAnswered == 1) {
                        Thread.sleep(900);
                        server.emitAudio(new byte[960]);
                    }
                    server.emitUsage(utterancesAnswered * 3.0);
                }
                int appends = server.received("session.instructions.append").size();
                for (int n = appendsSeen + 1; n <= appends; n++) {
                    server.emit(Map.of("type", "session.updated", "session", Map.of("instructions", "x".repeat(100 + n * 40))));
                }
                appendsSeen = Math.max(appendsSeen, appends);
                if (!created && !server.received("response.create").isEmpty()) {
                    created = true;
                    server.emitAudio(new byte[960]);
                    Thread.sleep(20);
                    server.emitAudio(new byte[960]);
                }
                if (!server.received("session.close").isEmpty()) {
                    server.emit(Map.of("type", "session.closed", "reason", "client"));
                    closed = true;
                }
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IllegalStateException e) {
                // 아직 연결이 없거나 이미 닫혔다
            }
        }
    }
}
