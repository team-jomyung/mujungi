package kr.mujoong.realtime.luna;

import static org.assertj.core.api.Assertions.assertThat;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.RealtimeDb;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.realtime.usage.AiUsageLimitsProperties;
import kr.mujoong.realtime.usage.AiUsageLiveSupport;
import kr.mujoong.realtime.usage.AiUsageProperties;
import kr.mujoong.realtime.usage.AiUsageRecorder;
import kr.mujoong.realtime.usage.AiUsageRepository;
import kr.mujoong.realtime.usage.AiUsageTimingProperties;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@Tag("luna-live")
@Tag("ai-usage")
class AiUsageLunaLiveTest {
    @Test void recordsActualResponseTokensWithCollectionOffAndOn() throws Exception {
        AiUsageLiveSupport.requireKey("luna");
        var clock = new SystemMujoongClock();
        var json = JsonCodec.shared();
        var probe = LunaReachProbe.live();
        var properties = probe.properties();
        var timing = AiUsageLiveSupport.setting("mujoong.timing.ai-usage", AiUsageTimingProperties.class);
        var limits = AiUsageLiveSupport.setting("mujoong.limits.ai-usage", AiUsageLimitsProperties.class);
        try (RealtimeDb db = RealtimeDb.open()) {
            UUID sessionId = AiUsageLiveSupport.session(db, clock);
            for (boolean enabled : new boolean[] {false, true}) {
                UUID turn = UUID.randomUUID();
                db.jdbc().update("""
                        insert into realtime.realtime_judgment_live
                        (id,guardian_id,session_id,judgment_point,trigger_kind,input_transcript_seq,option_set_version,
                         prompt_version,outcome,abstain_reason,turn_opened,latency_millis,next_action,created_at)
                        select :id,guardian_id,id,'J6','EXTENSION_REQUEST',0,'synthetic','synthetic',
                               'ABSTAINED','TIMEOUT',true,0,'NONE',created_at
                        from realtime.realtime_activity_session where id=:session
                        """, Map.of("id", turn, "session", sessionId));
                var metrics = new SimpleMeterRegistry();
                var recorder = new AiUsageRecorder(new AiUsageRepository(db.jdbc()), new DataSourceTransactionManager(db.dataSource()),
                        clock, new AiUsageProperties(enabled), timing, limits, metrics);
                recorder.start();
                try (var transport = new LunaTransport(properties.baseUrl(), LunaHosts.Mode.STRICT,
                        () -> System.getenv(LunaTransport.API_KEY_ENV), properties.maxResponseBytes())) {
                    var client = new RealtimeLunaClient(properties, LunaReachProbe.LIVE_BUDGET, LunaSchemas.loadDefault(json),
                            new LunaInputSanitizer(json), transport,
                            TestEventLoggers.strict(clock, LogCapture.detached(), RealtimeMetrics.inMemory()), clock, json, recorder);
                    var request = new LunaRequest(LunaPurpose.JUDGMENT_LIVE, LunaReachProbe.PROMPT_VERSION,
                            LunaReachProbe.INSTRUCTIONS, LunaReachProbe.j6Input(probe.j6Options()), Map.of(),
                            clock.now().plus(LunaReachProbe.LIVE_BUDGET));
                    long began = clock.monotonicNanos();
                    try { client.decide(request, LunaReachProbe.question(request), turn).toCompletableFuture().get(10, TimeUnit.SECONDS); }
                    catch (java.util.concurrent.ExecutionException failure) {
                        AiUsageLiveSupport.report("luna", "REAL_API_SYNTHETIC enabled=" + enabled + " businessOutcome="
                                + (failure.getCause() instanceof LunaException e ? e.reason().name() : "UNKNOWN"));
                    }
                    long elapsed = clock.elapsedSince(began).toMillis();
                    // 실제 단일 저장 작업을 사용한다. 업무 결과와 사용량 저장 완료는 별개다.
                    if (enabled) {
                        long waiting = clock.monotonicNanos();
                        while (clock.elapsedSince(waiting).compareTo(timing.shutdownWait()) < 0) {
                            Long count = db.jdbc().queryForObject("select count(*) from realtime.realtime_ai_usage where turn_id=:id",
                                    Map.of("id", turn), Long.class);
                            if (count != null && count > 0) break;
                            Thread.sleep(20);
                        }
                    }
                    recorder.stop();
                    var rows = db.jdbc().queryForList("select capture_status,input_tokens,output_tokens from realtime.realtime_ai_usage where turn_id=:id", Map.of("id", turn));
                    AiUsageLiveSupport.report("luna", "REAL_API_SYNTHETIC enabled=" + enabled + " elapsedMs=" + elapsed + " rows=" + rows.size());
                    if (enabled) {
                        assertThat(rows).hasSize(1);
                        AiUsageLiveSupport.report("luna", "status=" + rows.getFirst().get("capture_status")
                                + " inputTokens=" + rows.getFirst().get("input_tokens") + " outputTokens=" + rows.getFirst().get("output_tokens"));
                        assertThat(rows.getFirst().get("capture_status")).isEqualTo("COMPLETE");
                    } else assertThat(rows).isEmpty();
                } finally { recorder.stop(); metrics.close(); }
            }
            db.jdbc().update("delete from realtime.realtime_ai_usage where session_id=:id", Map.of("id", sessionId));
            db.jdbc().update("delete from realtime.realtime_judgment_live where session_id=:id", Map.of("id", sessionId));
            db.jdbc().update("delete from realtime.realtime_activity_session where id=:id", Map.of("id", sessionId));
        }
        AiUsageLiveSupport.report("luna", "OBSERVED_ONLY · 각 상태 1회 · 지연 차이는 전체 대화 성능 합격 판정에 사용하지 않음");
    }
}
