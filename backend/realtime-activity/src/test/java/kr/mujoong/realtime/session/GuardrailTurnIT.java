package kr.mujoong.realtime.session;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.message.OutputBegin;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.guardrail.GuardrailClient;
import kr.mujoong.realtime.guardrail.GuardrailMode;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.log.TranscriptRepository;
import kr.mujoong.realtime.log.TranscriptRow;
import kr.mujoong.realtime.testing.FakeGuardrailClient;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 제브 게이트 한 턴 통합 시험 (U13 FR3 · FR4 · FR6.4 · BR3.x ~ BR5.x · BR8.4): 실제 DB · test 프로필 앱 · 기기 시뮬레이터 · 가짜 공급자 WS 서버로, 운영 값의
 * 구간 조용 시간(800ms)에서 ALLOW 한 턴이 기기로 나가고, BLOCK이면 0바이트 + 재지시 + 보호자 화면(12 · 27이 읽는 승인된 행)에 나오지 않고, 늦은 ALLOW는
 * 무효이며, 검사 중 새 구간은 대기열에서 순서대로 검사됨을 본다.
 *
 * <p>제브는 시험이 손으로 푸는 대역이다(앱 문맥에 {@code @Primary} 포트로 끼움). 실제 제브 · 실제 공급자 · 실제 기기를 부르지 않는다 — 이 시험의 합격은
 * 테스트 대역 증거일 뿐 실제 검사 성공이 아니다(R-F1 · R-M11).
 */
@Tag("u13")
@Tag("db")
class GuardrailTurnIT {

    private static final byte[] PCM = new byte[4800];
    private static final String CLEAN = "나도 공룡 좋아해";

    private final FakeGuardrailClient guard = new FakeGuardrailClient(GuardrailMode.STUB);
    private RealtimeItEnvironment env;
    private DeviceSimulatorClient device;
    private UUID sessionId;

    @BeforeEach
    void start() {
        // 구간 조용 시간은 시험 프로필의 50ms가 아니라 운영값 800ms로 둔다(검사 상한 800ms와 같은 눈금에서 본다)
        env = RealtimeItEnvironment.startWith(Map.of("mujoong.gate.segment-quiet", "800ms"), withFake(guard));
        device = env.connectDevice();
        sessionId = activate();
    }

    @AfterEach
    void stop() {
        env.close();
    }

    @Test
    void anAllowedTurnReachesTheDeviceOnlyAfterTheAllowAndIsApprovedForTheGuardianViewThen() {
        env.provider.emitTurn("item-1", "공룡이 좋아", CLEAN, List.of(PCM, PCM));
        awaitTrue(() -> guard.count() == 1, "제브 요청 한 건");

        // ALLOW 전에는 기기로 한 바이트도 가지 않고, 보호자 화면이 읽는 승인된 AI 행도 없다
        assertThat(device.received(WsMessageType.OUTPUT_BEGIN)).isEmpty();
        assertThat(device.outputFrames()).isZero();
        assertThat(visibleAiRows()).isEmpty();
        assertThat(guard.last().request().candidate()).isEqualTo(CLEAN);

        guard.allow();
        WsEnvelope begin = device.await(WsMessageType.OUTPUT_BEGIN, WAIT);
        device.await(WsMessageType.OUTPUT_END, WAIT);

        assertThat(((OutputBegin) begin.payload()).source()).isEqualTo(OutputBegin.Source.GPT_LIVE);
        assertThat(device.outputPcm()).hasSize(PCM.length * 2);
        awaitTrue(() -> visibleAiRows().size() == 1, "승인된 AI 행");
        assertThat(visibleAiRows().getFirst().text()).isEqualTo(CLEAN);
        awaitTrue(() -> events().stream().anyMatch(event -> "ALLOW".equals(event.get("decision"))), "ALLOW 검사 기록");
        assertThat(events()).singleElement().satisfies(event -> {
            assertThat(event.get("source_kind")).isEqualTo("NEW_UTTERANCE");
            assertThat(event.get("mode")).isEqualTo("STUB");
        });
    }

    @Test
    void aBlockedTurnSendsZeroBytesRetriesOnceAndNeverShowsOnTheGuardianView() {
        int requestsBefore = env.provider.received().size();
        env.provider.emitTurn("item-1", "공룡이 좋아", CLEAN, List.of(PCM, PCM));
        awaitTrue(() -> guard.count() == 1, "제브 요청 한 건");

        guard.block();

        // 막힘: 기기로 0바이트, 재지시 한 번(공급자로 지시가 더 나간다)
        awaitTrue(() -> env.provider.received().size() > requestsBefore, "재지시 요청");
        awaitTrue(() -> aiRows().stream().anyMatch(TranscriptRow::isFinal), "막힌 AI 행 FINAL");
        assertThat(device.received(WsMessageType.OUTPUT_BEGIN)).isEmpty();
        assertThat(device.outputFrames()).isZero();
        // 막힌 문장은 승인 거짓으로 남고(지금 동작), 12 · 27이 읽는 승인된 행으로는 나오지 않는다(BR8.4)
        assertThat(aiRows()).allSatisfy(row -> assertThat(row.approvedForDisplay()).isFalse());
        assertThat(visibleAiRows()).isEmpty();
        awaitTrue(() -> events().stream().anyMatch(event -> "BLOCK".equals(event.get("decision"))), "BLOCK 검사 기록");

        // 재지시 응답도 같은 검사를 거친다(건너뛰지 않는다)
        env.provider.emitSpeech("다른 이야기를 해 볼까", List.of(PCM));
        awaitTrue(() -> guard.count() == 2, "재지시 응답의 제브 요청");
        assertThat(device.received(WsMessageType.OUTPUT_BEGIN)).isEmpty();
    }

    @Test
    void aLateAllowAfterTheChildSpokeAgainIsVoidedAndNeverPlays() {
        env.provider.emitTurn("item-1", "공룡이 좋아", CLEAN, List.of(PCM, PCM));
        awaitTrue(() -> guard.count() == 1, "제브 요청 한 건");

        // 요청 뒤 아이가 다시 말했다(새 입력 묶음이 열렸다)
        env.childSays("item-2", "잠깐만");
        RealtimeItEnvironment.sleep(200);
        guard.allow();

        awaitTrue(() -> events().stream().anyMatch(event -> "VOIDED".equals(event.get("decision"))), "VOIDED 검사 기록");
        assertThat(device.received(WsMessageType.OUTPUT_BEGIN)).isEmpty();
        assertThat(device.outputFrames()).isZero();
        assertThat(visibleAiRows()).isEmpty();
        assertThat(events()).noneMatch(event -> "ALLOW".equals(event.get("decision")));
    }

    @Test
    void aSegmentSealedWhileACheckIsPendingWaitsInTheQueueAndIsCheckedInOrderAfterTheFirstIsSent() {
        env.provider.emitTurn("item-1", "공룡이 좋아", CLEAN, List.of(PCM, PCM));
        awaitTrue(() -> guard.count() == 1, "첫 제브 요청");
        FakeGuardrailClient.Call first = guard.last();

        env.provider.emitSpeech("티라노도 좋아하니", List.of(PCM, PCM));
        RealtimeItEnvironment.sleep(1_200);
        assertThat(guard.count()).as("대기 구간은 앞 검사가 끝나기 전에 검사하지 않는다").isEqualTo(1);

        guard.allow(first);
        awaitTrue(() -> device.received(WsMessageType.OUTPUT_BEGIN).size() == 1, "첫 구간 송신");
        awaitTrue(() -> guard.count() == 2, "대기 구간의 제브 요청");
        assertThat(guard.last().request().candidate()).isEqualTo("티라노도 좋아하니");

        guard.allow();
        awaitTrue(() -> device.received(WsMessageType.OUTPUT_BEGIN).size() == 2, "대기 구간 송신");
        awaitTrue(() -> visibleAiRows().size() == 2, "승인된 AI 행 둘");
        assertThat(visibleAiRows()).extracting(TranscriptRow::text).containsExactly(CLEAN, "티라노도 좋아하니");
    }

    // ---- 도우미 ----

    private static ApplicationContextInitializer<ConfigurableApplicationContext> withFake(GuardrailClient fake) {
        return context -> ((BeanDefinitionRegistry) context.getBeanFactory()).registerBeanDefinition(
                "manualGuardrailClient",
                BeanDefinitionBuilder.genericBeanDefinition(GuardrailClient.class, () -> fake).setPrimary(true)
                        .getBeanDefinition());
    }

    private UUID activate() {
        UUID id = UUID.randomUUID();
        env.start(id);
        WsEnvelope startSession = device.await(WsMessageType.START_SESSION, WAIT);
        device.ack(startSession.opId(), id);
        device.deviceReady(id, true, true);
        env.join(id);
        awaitTrue(() -> env.sessionStatus(id) == SessionStatus.ACTIVE, "ACTIVE");
        device.await(envelope -> envelope.payload() instanceof SessionState state && state.view() == View.LISTENING, WAIT);
        device.heartbeat(30L);
        return id;
    }

    private List<TranscriptRow> aiRows() {
        return env.app.bean(TranscriptRepository.class).findAll(sessionId).stream()
                .filter(row -> row.speaker() == TranscriptEntry.Speaker.AI).toList();
    }

    /** 보호자 화면 12 · 27이 읽는 행(아이 행 + 승인된 FINAL AI 행) 중 AI 행. */
    private List<TranscriptRow> visibleAiRows() {
        return env.app.bean(TranscriptRepository.class).findVisibleAfter(sessionId, 0).stream()
                .filter(row -> row.speaker() == TranscriptEntry.Speaker.AI).toList();
    }

    private List<Map<String, Object>> events() {
        return env.app.bean(NamedParameterJdbcTemplate.class).queryForList(
                "select decision, source_kind, mode from realtime.guardrail_event where session_id = :sessionId",
                new MapSqlParameterSource("sessionId", sessionId));
    }
}
