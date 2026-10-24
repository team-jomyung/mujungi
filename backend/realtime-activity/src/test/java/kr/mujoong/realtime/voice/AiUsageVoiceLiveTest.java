package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.testing.RealtimeDb;
import kr.mujoong.realtime.usage.AiUsageLimitsProperties;
import kr.mujoong.realtime.usage.AiUsageLiveSupport;
import kr.mujoong.realtime.usage.AiUsageProperties;
import kr.mujoong.realtime.usage.AiUsageRecorder;
import kr.mujoong.realtime.usage.AiUsageRepository;
import kr.mujoong.realtime.usage.AiUsageTimingProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 공급자 연결 경계의 실제 사용량·DB 기록 비교. 전체 대화 성능 합격 시험을 대신하지 않는다. */
@Tag("gpt-live")
@Tag("ai-usage")
class AiUsageVoiceLiveTest {
    @Test void comparesCollectionOffAndOnWithTheRealVoiceProvider() throws Exception {
        AiUsageLiveSupport.requireKey("voice");
        var timing = AiUsageLiveSupport.setting("mujoong.timing.ai-usage", AiUsageTimingProperties.class);
        var limits = AiUsageLiveSupport.setting("mujoong.limits.ai-usage", AiUsageLimitsProperties.class);
        Object configured = new YamlPropertySourceLoader().load("voice-live", new ClassPathResource("application-demo.yml"))
                .stream().map(p -> p.getProperty("mujoong.provider.url")).filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        String override = System.getenv("MUJOONG_PROVIDER_URL");
        URI endpoint = URI.create(override == null || override.isBlank() ? configured.toString() : override);
        assertThat(ProviderHosts.isConnectable(endpoint, "demo")).isTrue();
        try (RealtimeDb db = RealtimeDb.open()) {
            var clock = new SystemMujoongClock();
            for (boolean enabled : new boolean[] {false, true}) {
                UUID sessionId = AiUsageLiveSupport.session(db, clock);
                var metrics = new SimpleMeterRegistry();
                var recorder = new AiUsageRecorder(new AiUsageRepository(db.jdbc()), new DataSourceTransactionManager(db.dataSource()),
                        clock, new AiUsageProperties(enabled), timing, limits, metrics);
                var client = new ProviderClient(endpoint, System.getenv(ProviderClient.API_KEY), JsonCodec.shared(),
                        Duration.ofSeconds(10));
                recorder.start();
                try {
                    var started = new CompletableFuture<Void>();
                    var firstAudio = new CompletableFuture<Long>();
                    var usageSeen = new CompletableFuture<Double>();
                    AtomicLong sentAt = new AtomicLong();
                    ProviderPort.Listener observer = event -> {
                        if (event instanceof ProviderPort.ProviderEvent.Started) started.complete(null);
                        if (event instanceof ProviderPort.ProviderEvent.UsageUpdated usage
                                && Double.isFinite(usage.seconds()) && usage.seconds() >= 0) {
                            usageSeen.complete(usage.seconds());
                        }
                        if (event instanceof ProviderPort.ProviderEvent.OutputAudioDelta && sentAt.get() > 0) {
                            firstAudio.complete((clock.monotonicNanos() - sentAt.get()) / 1_000_000L);
                        }
                    };
                    var factory = new ProviderRequestFactory();
                    var start = factory.sessionStart(new ChildContext("합성", 8, List.of("그림")));
                    ProviderPort.Listener listener = enabled ? new ProviderUsageListener(sessionId, start.session().model(), observer,
                            record -> {
                                boolean accepted = recorder.offer(record);
                                if (record.voiceSeconds() != null) usageSeen.complete(record.voiceSeconds());
                                return accepted;
                            }, clock) : observer;
                    var session = client.open(listener, Duration.ofSeconds(10));
                    try {
                        session.start(start);
                        started.get(10, TimeUnit.SECONDS);
                        sentAt.set(clock.monotonicNanos());
                        session.appendInstructions(factory.instructionsAppend(UUID.randomUUID().toString(),
                                "성인 개발자의 합성 시험이다. 짧게 인사 한 문장만 말한다."));
                        long first = firstAudio.get(20, TimeUnit.SECONDS);
                        // 시험만 중간 갱신을 기다린다. 운영 close()에는 사용량 대기를 넣지 않는다.
                        usageSeen.get(30, TimeUnit.SECONDS);
                        session.close(factory.sessionClose());
                        recorder.stop();
                        var rows = db.jdbc().queryForList("select capture_status, voice_seconds from realtime.realtime_ai_usage where session_id=:id", Map.of("id", sessionId));
                        AiUsageLiveSupport.report("voice", "REAL_API_SYNTHETIC enabled=" + enabled + " firstAudioBoundaryMs=" + first + " rows=" + rows.size());
                        if (enabled) {
                            assertThat(rows).hasSize(1);
                            AiUsageLiveSupport.report("voice", "status=" + rows.getFirst().get("capture_status") + " voiceSeconds=" + rows.getFirst().get("voice_seconds"));
                            assertThat(rows.getFirst().get("capture_status")).isIn("PARTIAL", "COMPLETE");
                            assertThat(rows.getFirst().get("voice_seconds")).isInstanceOf(Number.class);
                            assertThat(((Number) rows.getFirst().get("voice_seconds")).doubleValue()).isGreaterThanOrEqualTo(0);
                        } else assertThat(rows).isEmpty();
                    } finally { session.close(); }
                } finally { recorder.stop(); client.destroy(); metrics.close(); }
                db.jdbc().update("delete from realtime.realtime_ai_usage where session_id=:id", Map.of("id", sessionId));
                db.jdbc().update("delete from realtime.realtime_activity_session where id=:id", Map.of("id", sessionId));
            }
        }
        AiUsageLiveSupport.report("voice", "OBSERVED_ONLY · 각 상태 1회 · 네트워크·모델 변동 포함 · 전체 대화 지연 합격 근거 아님");
    }
}
