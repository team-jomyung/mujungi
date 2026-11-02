package kr.mujoong.realtime.voice;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 실호출 진단: 아이 입력이 무음뿐일 때 승인 문장 낭독 지시({@code session.instructions.append})를 넣으면 GPT-Live가 입력 전사
 * ({@code session.input_transcript.delta})를 내는지 본다. 2026-10-30 실키에서 무중이가 읽을 승인 문장이 소리가 나기도 전에 아이 전사로 들어왔다.
 * 운영 공장({@link ProviderRequestFactory#practiceInstruction})과 운영 낭독 지시({@link PracticeInstruction#forSpeech})를 그대로 쓰고, 생성 발화
 * (commentary) 지시와 비교한다. 원문은 메모리에서만 견주고 결과 파일에는 일치 여부 · 글자 수 · 시간만 쓴다(R-F14). 기본 test에서 빠지고
 * gptLiveTest로만 돈다.
 */
@Tag("gpt-live")
class GptLiveInstructionEchoTest {
    private static final JsonCodec JSON = JsonCodec.shared();
    private static final String SPEECH = "이 책꽂이는 책상 위에 놓기 딱 좋겠다. 너는 어떤 책을 꽂고 싶어?";

    @Test
    void approvedReadingOnSilentInputIsCheckedForInputTranscripts() throws Exception {
        String key = System.getenv(ProviderClient.API_KEY);
        assumeTrue(key != null && !key.isBlank(), "SKIPPED_NO_KEY");
        int trials = Integer.parseInt(System.getenv().getOrDefault("ECHO_TRIALS", "3"));
        Path out = Path.of("build/gpt-live-instruction-echo.jsonl");
        Files.deleteIfExists(out);
        URI url = GptLiveProbe.providerUrl();
        var factory = new ProviderRequestFactory();
        for (int trial = 0; trial < trials; trial++) {
            for (String mode : List.of("APPROVED_READING", "GENERATED")) {
                var tap = new GptLiveProbe.Tap(JSON);
                var link = GptLiveProbe.openLikeProviderClient(url, key, tap);
                var session = link.session();
                var silence = new byte[24000 / 50 * 2];
                var running = new AtomicBoolean(true);
                session.start(factory.sessionStart(new ChildContext("하늘", 8, List.of()), 24000));
                long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < waitUntil && tap.events.stream().noneMatch(e -> e instanceof ProviderEvent.Started)) TimeUnit.MILLISECONDS.sleep(50);
                Thread pump = Thread.ofVirtual().start(() -> {
                    // 기기처럼 20ms마다 입력을 보낸다(전부 무음)
                    while (running.get()) {
                        try { session.sendInputAudio(factory.inputAudio(silence)); TimeUnit.MILLISECONDS.sleep(20); }
                        catch (Exception stop) { return; }
                    }
                });
                try {
                    TimeUnit.SECONDS.sleep(4);
                    long sentNanos = System.nanoTime();
                    String eventId = "echo-" + UUID.randomUUID();
                    var instruction = mode.equals("APPROVED_READING")
                            ? PracticeInstruction.forSpeech(PracticeSpeech.scenario(UUID.randomUUID(), UUID.randomUUID(), 1, "친구", SPEECH))
                            : PracticeInstruction.forGeneratedSpeech("친구 역할로 아이에게 책꽂이를 함께 만드는 이야기를 한 문장으로 가볍게 건네.");
                    session.appendInstructions(factory.practiceInstruction(eventId, instruction));
                    TimeUnit.SECONDS.sleep(15);
                    record(out, trial, mode, tap, sentNanos, eventId);
                } finally {
                    running.set(false);
                    pump.join(1000);
                    session.close(factory.sessionClose());
                    link.resources().close();
                }
            }
        }
    }

    /**
     * 운영과 가깝게: 한 세션에서 말소리 입력(합성 안내 음성) → 무중이 응답 → 승인 낭독 지시를 여러 번 되풀이한다. 그 사이 입력은 무음이다.
     * 입력 전사가 낭독 문장과 같은 글자로 나오는지, 그 시간이 지시 시간선 구간과 겹치는지 본다.
     */
    @Test
    void approvedReadingAfterSpokenTurnsIsCheckedForInputTranscripts() throws Exception {
        String key = System.getenv(ProviderClient.API_KEY);
        assumeTrue(key != null && !key.isBlank(), "SKIPPED_NO_KEY");
        int cycles = Integer.parseInt(System.getenv().getOrDefault("ECHO_CYCLES", "4"));
        Path out = Path.of("build/gpt-live-instruction-echo-turns.jsonl");
        Files.deleteIfExists(out);
        var factory = new ProviderRequestFactory();
        var wav = GptLiveProbe.readPcm16Mono(Path.of("..", "..", GptLiveProbe.DEFAULT_INPUT).normalize(), 24000);
        var speech = GptLiveProbe.frames(GptLiveProbe.resample(wav.pcm(), wav.rate(), 24000), 24000 / 50 * 2);
        var silence = new byte[24000 / 50 * 2];
        var tap = new GptLiveProbe.Tap(JSON);
        var link = GptLiveProbe.openLikeProviderClient(GptLiveProbe.providerUrl(), key, tap);
        var session = link.session();
        var speaking = new java.util.concurrent.ConcurrentLinkedQueue<byte[]>();
        var running = new AtomicBoolean(true);
        session.start(factory.sessionStart(new ChildContext("하늘", 8, List.of()), 24000));
        long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < waitUntil && tap.events.stream().noneMatch(e -> e instanceof ProviderEvent.Started)) TimeUnit.MILLISECONDS.sleep(50);
        Thread pump = Thread.ofVirtual().start(() -> {
            while (running.get()) {
                byte[] frame = speaking.poll();
                try { session.sendInputAudio(factory.inputAudio(frame == null ? silence : frame)); TimeUnit.MILLISECONDS.sleep(20); }
                catch (Exception stop) { return; }
            }
        });
        try {
            TimeUnit.SECONDS.sleep(3);
            for (int cycle = 0; cycle < cycles; cycle++) {
                speaking.addAll(speech);
                while (!speaking.isEmpty()) TimeUnit.MILLISECONDS.sleep(100);
                TimeUnit.SECONDS.sleep(8);
                String eventId = "echo-turn-" + cycle;
                session.appendInstructions(factory.practiceInstruction(eventId,
                        PracticeInstruction.forSpeech(PracticeSpeech.scenario(UUID.randomUUID(), UUID.randomUUID(), cycle + 1, "친구", SPEECH))));
                TimeUnit.SECONDS.sleep(12);
            }
            record(out, 0, "TURNS", tap, 0, "echo-turn-0");
            // 지시마다 시간선 구간도 남긴다
            var windows = new ArrayList<Map<String, Object>>();
            for (var event : new ArrayList<>(tap.events))
                if (event instanceof ProviderEvent.InstructionsAppended a && a.clientEventId() != null && a.clientEventId().startsWith("echo-turn-"))
                    windows.add(Map.of("id", a.clientEventId(), "startMs", a.startMs(), "endMs", a.endMs()));
            Files.writeString(out, JSON.write(Map.of("instructionWindows", windows)) + "\n", java.nio.file.StandardOpenOption.APPEND);
        } finally {
            running.set(false);
            pump.join(1000);
            session.close(factory.sessionClose());
            link.resources().close();
        }
    }

    private static void record(Path out, int trial, String mode, GptLiveProbe.Tap tap, long sentNanos, String eventId) throws Exception {
        List<ProviderEvent> events = new ArrayList<>(tap.events);
        long appendedStart = -1, appendedEnd = -1;
        var inputs = new ArrayList<Map<String, Object>>();
        var output = new StringBuilder();
        long firstOutputStart = -1;
        int audioChunks = 0;
        for (var event : events) {
            switch (event) {
                case ProviderEvent.InstructionsAppended appended when eventId.equals(appended.clientEventId()) -> {
                    appendedStart = appended.startMs(); appendedEnd = appended.endMs();
                }
                case ProviderEvent.InputTranscriptDelta delta -> {
                    String text = delta.delta() == null ? "" : delta.delta();
                    String compact = text.replaceAll("[\\s.,!?]", "");
                    // 원문은 남기지 않는다: 낭독 문장 · 출력 전사에 들어 있는지만 본다
                    inputs.add(Map.of("startMs", delta.startMs(), "endMs", delta.endMs(), "chars", compact.length(),
                            "inSpeech", !compact.isEmpty() && SPEECH.replaceAll("[\\s.,!?]", "").contains(compact)));
                }
                case ProviderEvent.OutputTranscriptDelta delta -> {
                    if (firstOutputStart < 0) firstOutputStart = delta.startMs();
                    output.append(delta.delta());
                }
                case ProviderEvent.OutputAudioDelta ignored -> audioChunks++;
                default -> { }
            }
        }
        var row = new LinkedHashMap<String, Object>();
        row.put("trial", trial); row.put("mode", mode);
        row.put("appendedStartMs", appendedStart); row.put("appendedEndMs", appendedEnd);
        row.put("firstOutputTranscriptStartMs", firstOutputStart); row.put("outputAudioChunks", audioChunks);
        row.put("outputChars", output.toString().replaceAll("\\s", "").length());
        row.put("inputTranscriptDeltas", inputs);
        row.put("eventTypes", events.stream().map(e -> e.getClass().getSimpleName()).distinct().toList());
        Files.writeString(out, JSON.write(row) + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
}
