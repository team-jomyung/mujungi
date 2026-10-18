package kr.mujoong.realtime.session;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.WsPayload;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.DeviceState;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.StartSession;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.gate.ApprovedPlayback;
import kr.mujoong.realtime.gate.ClosingSequence;
import kr.mujoong.realtime.gate.OutputGeneration;
import kr.mujoong.realtime.gateway.DeviceLink;
import kr.mujoong.realtime.log.SessionGapRepository;
import kr.mujoong.realtime.recording.AudioRecordings;
import kr.mujoong.realtime.voice.ProviderConnector;
import kr.mujoong.realtime.voice.ProviderPort.ProviderSession;
import kr.mujoong.realtime.session.SessionMailbox.ConnectionLost;
import kr.mujoong.realtime.session.SessionMailbox.DeviceStateReceived;
import kr.mujoong.realtime.session.SessionMailbox.EndOutcome;
import kr.mujoong.realtime.session.SessionMailbox.EndRequested;
import kr.mujoong.realtime.session.SessionMailbox.LinkReplaced;
import kr.mujoong.realtime.session.SessionMailbox.Message;
import kr.mujoong.realtime.session.SessionMailbox.PlaybackMarkReceived;
import kr.mujoong.realtime.session.SessionMailbox.VoiceClosed;
import kr.mujoong.realtime.session.SessionTimers.Kind;
import kr.mujoong.realtime.session.TransitionTable.Trigger;

import static kr.mujoong.realtime.session.SessionContext.START_PHASE;

/**
 * 세션 실행기의 기기 표식 · 끝내기 구역이다: 재생 표식 · 기기 상태 · 연결 교체 · 연결 끊김, 끝내기 요청(보호자 정지 · 동의 철회 · Core 포기),
 * CLOSING 진입, 공급자 닫힘 뒤 ENDED 전이를 처리한다.
 *
 * <p>세션 실행기 스레드에서만 불리므로 잠금이 없다. 공유 상태는 {@link SessionContext}로 읽고 쓰고, 실행기에 남은 로직(전이 보조 · 정산 · 시계 ·
 * 연습 확장점)은 {@link Env}로 되돌려 부른다. 상태 전이 보조(전이 · DB 맞춤 · 실패 뒤처리 · 종료 정리)도 여기에 있다.
 */
public final class EndingFlow {

    /** U3 세션 이벤트 종류(payload에 전사 · 원음 없음). */
    static final String EVENT_LINK_REPLACED = "DEVICE_LINK_REPLACED";
    static final String EVENT_BARGE_IN = "BARGE_IN";
    static final String EVENT_MIC_LOST = "DEVICE_MIC_LOST";
    static final String EVENT_MIC_OK = "DEVICE_MIC_OK";
    static final String EVENT_PLAYER_ERROR = "DEVICE_PLAYER_ERROR";

    /** 끝내기 구역이 실행기에 남은 로직으로 되돌려 부르는 자리. 모두 실행기 스레드에서 불린다. */
    interface Env {
        long bumpOutput(OutputGeneration.BumpReason reason);

        /** DB가 메모리보다 앞선 비종료 상태를 받아들였을 때 그 상태의 시한 · 부수 효과를 한 번 건다. */
        void adopt(ActivitySessionRow row, UUID endRequestId);

        void listeningAgain();

        void approvedBubble(SessionExecutor.ApprovedItem item, Instant at);

        void onJoinPromptPlayed(Instant at);

        void onStopConfirmPlayed(Instant at);

        void armDisconnect();

        void stopPracticeProgress();

        void cancelPracticeTimer(String name);

        void cancelPracticeTimers();

        void practicePlayback(kr.mujoong.realtime.voice.PracticeSpeech speech,
                kr.mujoong.realtime.practice.PracticePorts.Playback fact, Instant receivedAt);

        Map<UUID, kr.mujoong.realtime.voice.PracticeSpeech> practiceAudio();

        Map<UUID, String> practicePlaybacks();

        void guardrailTurnBoundary();

        /** 묶던 입력 전사 조각이 열려 있는가. */
        boolean inputGroupOpen();

        void flushInputGroupInSettle();

        /** 연습 확장점이 출력을 쥐고 있으면 정산을 미룰 남은 시간, 아니면 0이다. */
        Duration practiceSettlementDelay();

        /** 연습 확장점의 정산 시점을 한 번 부르고 practice 값을 받는다(확장점이 없거나 예외가 났으면 null). */
        Object practiceRecordForSettle(Instant cutoff);
    }

    private final SessionContext ctx;
    private final IdleClock idle;
    private final JudgmentCoordinator judgment;
    private final GuardrailCoordinator guardrail;
    private final Runnable onTerminated;
    private final Env env;

    private DeviceState.Mic lastMic;
    private DeviceState.Player lastPlayer;
    private UUID progressPlaybackId;
    private Long progressPlayedToMs;

    EndingFlow(SessionContext ctx, IdleClock idle, JudgmentCoordinator judgment, GuardrailCoordinator guardrail,
            Runnable onTerminated, Env env) {
        this.ctx = ctx;
        this.idle = idle;
        this.judgment = judgment;
        this.guardrail = guardrail;
        this.onTerminated = onTerminated;
        this.env = env;
    }

    void onPlaybackMark(PlaybackMarkReceived received) {
        if ((ctx.status().isTerminal() && !farewellPending()) || !ctx.fromBoundConnection(received.connectionId())) {
            return;
        }
        idle.deviceHeard();
        PlaybackMark mark = received.mark();
        var speech = env.practiceAudio().get(mark.playbackId());
        if (speech != null && mark.status() != PlaybackMark.Status.PROGRESS) {
            var fact = switch (mark.status()) {
                case STARTED -> kr.mujoong.realtime.practice.PracticePorts.Playback.STARTED;
                case COMPLETED -> kr.mujoong.realtime.practice.PracticePorts.Playback.COMPLETED;
                case STOPPED -> kr.mujoong.realtime.practice.PracticePorts.Playback.INTERRUPTED;
                default -> kr.mujoong.realtime.practice.PracticePorts.Playback.FAILED;
            };
            if (mark.status() != PlaybackMark.Status.STARTED) {
                env.practiceAudio().remove(mark.playbackId()); env.cancelPracticeTimer("u5b-audio-" + mark.playbackId());
            }
            env.practicePlayback(speech, fact, received.serverRecvAt());
        }
        if (ctx.status() == SessionStatus.DECLINED) {
            // U4b BR4.7: DECLINED 마무리 인사의 끝 표식 → CLOSE_TRANSPORT(DECLINED) → 정리
            if (ctx.closing().onPlaybackMark(closingDevice(), mark.playbackId(), mark.status())) {
                finishFarewell(true);
            }
            return;
        }
        DeviceLink current = ctx.link();
        switch (mark.status()) {
            case STARTED -> {
                ctx.devicePlaying().add(mark.playbackId());
                ctx.services().latency().playbackStarted(ctx.plan().sessionId(), mark.playbackId(), mark.observedAt(),
                        received.serverRecvAt(), current == null ? null : current.clockOffsetMs(),
                        current == null ? null : current.rttMs());
            }
            case PROGRESS -> {
                // U3-J10: 재생 진행(1초마다)은 끼어들기 · 멈춤 시점의 재생 위치 근거로만 둔다
                progressPlaybackId = mark.playbackId();
                progressPlayedToMs = mark.playedRange() == null ? null : mark.playedRange().toMs();
            }
            case COMPLETED, STOPPED, FAILED -> {
                if (mark.status() == PlaybackMark.Status.STOPPED) {
                    logStopped(mark, received.serverRecvAt(), current);
                    if (mark.stopCause() == PlaybackMark.StopCause.LOCAL_VOICE_ACTIVITY) {
                        onBargeIn(mark, received.serverRecvAt());
                    }
                }
                if (ctx.playback().clearIfMatches(mark.playbackId())) {
                    ctx.timers().cancel(Kind.PLAYBACK_EXPIRY);
                }
                if (ctx.status() == SessionStatus.WAITING_CHILD && mark.playbackId().equals(ctx.joinPromptPlaybackId())) {
                    // U4b BR2.2: 참여 안내의 끝 표식을 서버가 받은 시각부터 창을 연다
                    ctx.joinPromptPlaybackId(null);
                    env.onJoinPromptPlayed(received.serverRecvAt());
                }
                String practiceCode = env.practicePlaybacks().remove(mark.playbackId());
                if (practiceCode != null) {
                    // U4b BR10.4: 확장점이 요청한 재생의 끝 → PLAYBACK_DONE(편지함을 거쳐 실행기 스레드에서 전달)
                    ctx.mailbox().putSafety(new SessionMailbox.PracticeEvent(new PracticeSessionExtension.ExtensionEvent(
                            PracticeSessionExtension.ExtensionEvent.Kind.PLAYBACK_DONE, practiceCode, mark.playbackId(),
                            null)));
                }
                if (ctx.status() == SessionStatus.ACTIVE && mark.playbackId().equals(ctx.stopConfirmPlaybackId())) {
                    // U4b BR4.3: 확인 질문의 끝 표식을 서버가 받은 시각부터 창을 연다
                    ctx.stopConfirmPlaybackId(null);
                    env.onStopConfirmPlayed(received.serverRecvAt());
                }
                if (ctx.status() == SessionStatus.CLOSING && ctx.closing().onPlaybackMark(closingDevice(), mark.playbackId(),
                        mark.status())) {
                    // R3-8: CLOSE_TRANSPORT 송신까지만 입력을 중계한다
                    ctx.relay().setAccepting(false);
                    ctx.annotateQuietly(SessionStatus.CLOSING,
                            SessionChange.none().withTransportClosedAt(ctx.services().clock().now()));
                }
                ctx.devicePlaying().remove(mark.playbackId());
                // U3-J6: 재생이 모두 끝나면 다시 '듣는 중'
                env.listeningAgain();
            }
            default -> {
                // UNRECOGNIZED는 판단에 쓰지 않는다(J123)
            }
        }
    }

    /**
     * 끼어들기(U3-R5 · U3-J10 · AC6.2.12): 기기가 아이 음성으로 재생을 멈췄다. 남은 음성 프레임을 보내지 않고(세대 올림) 출력 세대를 올려
     * 공급자 응답의 남은 조각을 버리고(U11 BR3.4: 공급자 응답 취소 없음) 열린 출력 구간은 버리며 재생 위치와 함께 BARGE_IN을 남긴다. '듣는 중' 복귀는 끝 표식 처리가 한다(U3-J6).
     */
    private void onBargeIn(PlaybackMark mark, Instant at) {
        ctx.bumpGeneration();
        // U11 BR3.4: 공급자 응답 취소 대신 출력 세대를 올려 재생 중이던 · 만들던 응답의 남은 조각을 버린다(열린 구간도 버린다)
        env.bumpOutput(OutputGeneration.BumpReason.BARGE_IN);
        env.guardrailTurnBoundary();
        ctx.transcripts().aiFinal("", false, at);
        Long playedTo = mark.playedRange() != null ? mark.playedRange().toMs()
                : mark.playbackId().equals(progressPlaybackId) ? progressPlayedToMs : null;
        ctx.putEvent(EVENT_BARGE_IN, "{\"playbackId\":\"" + mark.playbackId() + "\""
                + (playedTo == null ? "" : ",\"playedToMs\":" + playedTo) + "}", at);
    }

    /** U3-J9: 묶인 기기의 마이크 끊김 · 복구와 플레이어 오류를 세션 이벤트로 남긴다(바뀔 때 한 번). */
    void onDeviceState(DeviceStateReceived received) {
        if (ctx.status().isTerminal() || !ctx.fromBoundConnection(received.connectionId())) {
            return;
        }
        idle.deviceHeard();
        DeviceState state = received.state();
        if (state.mic() != null && state.mic() != DeviceState.Mic.UNRECOGNIZED && state.mic() != lastMic) {
            if (state.mic() == DeviceState.Mic.LOST) {
                ctx.putEvent(EVENT_MIC_LOST, null, received.receivedAt());
            } else if (lastMic == DeviceState.Mic.LOST) {
                ctx.putEvent(EVENT_MIC_OK, null, received.receivedAt());
            }
            lastMic = state.mic();
        }
        if (state.player() != null && state.player() != DeviceState.Player.UNRECOGNIZED && state.player() != lastPlayer) {
            if (state.player() == DeviceState.Player.ERROR) {
                ctx.putEvent(EVENT_PLAYER_ERROR, null, received.receivedAt());
            }
            lastPlayer = state.player();
        }
    }

    /**
     * U3-J4 · J5 · J7: 같은 기기의 새 연결이 HELLO_OK를 받았다(HELLO_OK는 이미 나갔다). 세션을 새 연결에 묶고 끊김 구간을 닫은 뒤, 이어가기면
     * SESSION_STATE(지금 표시 상태)와 ACK를 못 받은 명령을, 이어가기가 아니면 START_SESSION(같은 opId · 같은 승인 음성)부터 다시 보낸다. 낡은
     * STOP_OUTPUT은 다시 보내지 않는다(새 연결의 음성 레인은 비어 있다).
     *
     * <p>리뷰 반영: 세션이 이미 끝났으면 새 연결을 대기로 돌려보낸다(4, {@link #standDown}). CLOSING 중 세션을 모르는 기기(resume 없음)에는
     * START_SESSION으로 입력 창을 다시 열게 하지 않고 CLOSE_TRANSPORT를 보낸다(5). 다시 보낸 승인 문구 재생은 재생 추적에 다시 넣는다(9).
     */
    void onLinkReplaced(LinkReplaced replaced) {
        if (ctx.status().isTerminal()) {
            standDown(replaced.link(), ctx.plan().sessionId(), ctx.status(), replaced.resumed());
            return;
        }
        DeviceLink next = replaced.link();
        DeviceLink previous = ctx.link();
        if (previous != null && previous.connectionId().equals(next.connectionId())) {
            return;
        }
        ctx.unbind(previous);
        ctx.link(next);
        next.bindSession(ctx.plan().sessionId());
        // SD-17: 연결 교체는 세대를 올린다(옛 묶음의 남은 프레임이 새 연결로 가지 않게)
        ctx.bumpGeneration();
        ctx.playback().clear();
        ctx.devicePlaying().clear();
        ctx.timers().cancel(Kind.PLAYBACK_EXPIRY);
        Instant now = ctx.services().clock().now();
        // U4b BR6.2: 다시 묶이면 일시정지를 끄고 구간을 닫는다
        idle.endPause(now);
        if (ctx.openGapId() != null) {
            ctx.services().gaps().close(ctx.openGapId(), now);
            ctx.openGapId(null);
        }
        // 재생 추적을 비웠으므로 끝 표식을 기다리던 참여 안내 · 확인 질문은 지금 끝난 것으로 본다(창이 영영 열리지 않는 일을 막는다)
        if (ctx.status() == SessionStatus.WAITING_CHILD && ctx.joinPromptPlaybackId() != null) {
            ctx.joinPromptPlaybackId(null);
            env.onJoinPromptPlayed(now);
        }
        if (ctx.status() == SessionStatus.ACTIVE && ctx.stopConfirmPlaybackId() != null) {
            ctx.stopConfirmPlaybackId(null);
            env.onStopConfirmPlayed(now);
        }
        ctx.putEvent(EVENT_LINK_REPLACED, "{\"resumed\":" + replaced.resumed() + "}", now);
        if (START_PHASE.contains(ctx.status())) {
            // 시작 단계: 기기가 이어가기를 안 했거나 START_SESSION의 ACK가 아직 없으면 같은 opId로 다시 보낸다(기기는 같은 opId를 다시 실행하지
            // 않고 ACK만 한다, U3-J8)
            if (ctx.startSent() && (!replaced.resumed() || ctx.pendingCommands().containsKey(ctx.startOpId()))) {
                next.sendControl(new StartSession(ctx.plan().approvedAudio()), ctx.startOpId(), ctx.plan().sessionId());
            }
            return;
        }
        if (ctx.status() == SessionStatus.CLOSING && !replaced.resumed()) {
            closeForUnknownDevice(now);
            return;
        }
        if (!replaced.resumed()) {
            // U3-J5: 기기가 세션을 모른다(새로 켠 페이지) — 같은 승인 음성으로 START_SESSION부터. 늦은 DEVICE_READY는 무시된다
            next.sendControl(new StartSession(ctx.plan().approvedAudio()), ctx.startOpId(), ctx.plan().sessionId());
        }
        ctx.sendControl(new SessionState(ctx.view()));
        keepOnlyLatestPendingPlayback();
        for (Map.Entry<UUID, WsPayload> pending : List.copyOf(ctx.pendingCommands().entrySet())) {
            WsPayload payload = pending.getValue();
            if (payload instanceof StopOutput || payload instanceof StartSession) {
                continue;
            }
            if (next.sendControl(payload, pending.getKey(), ctx.plan().sessionId())
                    && payload instanceof PlayApprovedContent play) {
                // 리뷰 반영 9: 같은 playbackId로 다시 보낸 승인 문구는 다시 재생될 것이라 추적한다(끝 표식 · 20초 만기가 지운다). 추적하지
                // 않으면 그 사이 대기 문구가 겹쳐 나가고 '듣는 중'이 재생 중에 나간다
                ctx.playback().started(play.playbackId(), play.contentCode());
                ctx.timers().arm(Kind.PLAYBACK_EXPIRY, ctx.services().settings().playbackExpiry());
            }
        }
        if (Trigger.DISCONNECT.accepts(ctx.status())) {
            env.armDisconnect();
        }
    }

    /**
     * 2차 리뷰 4: 재생 추적({@link ApprovedPlayback})은 한 칸이라 ACK 없는 승인 문구 재생 명령이 둘 이상이면 앞 것의 끝 표식이 추적을 지우지 못해
     * '듣는 중' 복귀가 늦는다. 새 연결에는 가장 최근 재생 명령 하나만 다시 보내고 앞 것은 미확인 목록에서 뺀다 — 끊기기 전 출력은 잇지 않는다는
     * 규칙(편차 (ab))과 같은 쪽이고, CLOSING의 마무리 문구는 늘 가장 최근 재생 명령이라(진입 때 STOP_OUTPUT 뒤 한 번) 남는다.
     */
    private void keepOnlyLatestPendingPlayback() {
        UUID latest = null;
        for (Map.Entry<UUID, WsPayload> pending : ctx.pendingCommands().entrySet()) {
            if (pending.getValue() instanceof PlayApprovedContent) {
                latest = pending.getKey();
            }
        }
        UUID keep = latest;
        ctx.pendingCommands().entrySet().removeIf(pending -> pending.getValue() instanceof PlayApprovedContent
                && !pending.getKey().equals(keep));
    }

    /**
     * 리뷰 반영 5: CLOSING 중 세션을 모르는 기기가 붙었다(CLOSE_TRANSPORT를 처리하고 세션을 잊은 뒤 끊긴 기기 · 새로 켠 페이지). START_SESSION을
     * 다시 보내면 기기가 마이크 입력 창을 다시 열어 30초 시한까지 열린 채 ENDED_UNCONFIRMED로 끝난다. 그래서 마무리 표시를 알리고
     * CLOSE_TRANSPORT를 (다시) 보내 VOICE_SESSION_CLOSED로 끝나게 한다. 아직 마무리 문구 차례였으면 세션을 모르는 기기가 재생하지 못하므로
     * 건너뛰고 지금 닫는다 — 입력 중계도 이때 멈춘다(R3-8). 옛 연결의 마무리 문구 · 닫기 명령은 다시 보내지 않는다.
     */
    private void closeForUnknownDevice(Instant now) {
        ctx.pendingCommands().values().removeIf(payload -> payload instanceof PlayApprovedContent
                || payload instanceof CloseTransport || payload instanceof StartSession);
        ctx.sendControl(new SessionState(View.CLOSING));
        if (ctx.closing().closeNow(closingDevice())) {
            ctx.relay().setAccepting(false);
            ctx.annotateQuietly(SessionStatus.CLOSING, SessionChange.none().withTransportClosedAt(now));
        }
    }

    /**
     * 리뷰 반영 4: 끝난 세션의 HELLO_OK.session을 받은 새 연결을 대기로 돌려보낸다. 이어간 기기(resumed)는 입력 창을 이미 다시 열었으므로
     * CLOSE_TRANSPORT로 닫게 하고(그 ACK · VOICE_SESSION_CLOSED는 세션이 없어 버려진다), 그다음 SESSION_STATE{STANDBY}. 연결이 닫혔으면 아무것도
     * 보내지 않는다. 실행기가 LinkReplaced를 처리하기 전에 끝났을 때(정리의 남은 편지 · 인계가 닫힌 편지함을 만났을 때) 쓴다.
     *
     * @param ended 세션의 끝 상태(닫기 사유를 고른다)
     */
    public static void standDown(DeviceLink next, UUID sessionId, SessionStatus ended, boolean resumed) {
        if (next == null || !next.isOpen()) {
            return;
        }
        UUID bound = next.boundSessionId();
        if (bound != null && !bound.equals(sessionId)) {
            // 2차 리뷰 5: 그 사이 같은 기기의 새 세션이 이 연결을 묶었다 — 뒤늦은 대기 명령으로 새 세션 표시를 덮지 않는다
            return;
        }
        if (resumed) {
            next.sendControl(new CloseTransport(closeReasonFor(ended)), UUID.randomUUID(), sessionId);
        }
        next.sendControl(new SessionState(View.STANDBY), null, sessionId);
    }

    private static CloseTransport.Reason closeReasonFor(SessionStatus ended) {
        return switch (ended) {
            case ENDED, ENDED_UNCONFIRMED -> CloseTransport.Reason.ENDED;
            case ABORTED -> CloseTransport.Reason.DISCONNECT;
            case DECLINED -> CloseTransport.Reason.DECLINED;
            default -> CloseTransport.Reason.FAILED;
        };
    }

    private void logStopped(PlaybackMark mark, Instant serverRecvAt, DeviceLink current) {
        ctx.services().events().log(EventCode.PLAYBACK_MARK_STOPPED, EventLog.Component.SESSION_LIFECYCLE, ctx.plan().sessionId(),
                ctx.plan().traceId(), entry -> {
                    entry.attr(AttrKey.STOP_CAUSE, mark.stopCause()).attr(AttrKey.T_DEVICE_OBSERVED, mark.observedAt())
                            .attr(AttrKey.T_SERVER_RECV, serverRecvAt);
                    if (current != null && current.clockOffsetMs() != null) {
                        entry.attr(AttrKey.CLOCK_OFFSET_MS, current.clockOffsetMs().longValue());
                    }
                    if (current != null && current.rttMs() != null) {
                        entry.attr(AttrKey.RTT_MS, current.rttMs().longValue());
                    }
                });
    }

    void onVoiceClosed(VoiceClosed closed) {
        if (ctx.status() != SessionStatus.CLOSING || !ctx.fromBoundConnection(closed.connectionId())) {
            return;
        }
        Instant now = ctx.services().clock().now();
        closeProvider();
        SessionChange change = TransitionTable.changeFor(Trigger.VOICE_CLOSED, null, now).withProviderClosedAt(now)
                .withOutputStoppedAt(closed.closed().outputStoppedAt());
        Optional<ActivitySessionRow> row = ctx.services().transitions().transition(ctx.plan().sessionId(), SessionStatus.CLOSING,
                SessionStatus.ENDED, change, SessionTransitions.Origin.REALTIME, ctx.plan().traceId());
        if (row.isEmpty()) {
            syncFromDb();
            return;
        }
        changeStatus(SessionStatus.ENDED, ctx.services().clock().now());
        ctx.bumpGeneration();
        ctx.sendControl(new SessionState(View.STANDBY));
        afterTerminal();
    }

    void onEndRequested(EndRequested request) {
        EndOutcome outcome = switch (request.reason()) {
            case GUARDIAN_STOP -> guardianStop(request.requestId());
            case CONSENT_WITHDRAWN -> consentWithdrawn(request.requestId());
            case CORE_ABANDONED -> coreAbandoned();
            default -> EndOutcome.UNSUPPORTED;
        };
        request.result().complete(outcome);
    }

    /**
     * J54 · U4b 응답표(BR1.5 · BR1.6): WAITING_CHILD · ACTIVE → CLOSING(GUARDIAN_STOP, 첫 수락), CLOSING → 같은 답(재처리 없음), 시작 단계 ·
     * 종료 → 409.
     */
    private EndOutcome guardianStop(UUID requestId) {
        if (ctx.status() == SessionStatus.CLOSING) {
            return EndOutcome.CLOSING;
        }
        if (!Trigger.GUARDIAN_STOP.accepts(ctx.status())) {
            return EndOutcome.NOT_ACTIVE;
        }
        beginClosing(Trigger.GUARDIAN_STOP, requestId);
        return ctx.status() == SessionStatus.CLOSING ? EndOutcome.CLOSING : EndOutcome.NOT_ACTIVE;
    }

    /**
     * U4b 응답표의 동의 철회 행(BR1.5 · BR1.7 · U4b-R9 · U4b-J9): 시작 단계 → FAILED(CONSENT_WITHDRAWN) + 202 {}, WAITING_CHILD · ACTIVE →
     * CLOSING(CONSENT_WITHDRAWN) + 202 {CLOSING}, CLOSING → 202 {CLOSING}(사유는 처음 것), 종료 → 409. 받은 순간부터 아이 전사 · 원음을 저장하지
     * 않는다(반복 요청은 같은 응답만, BR1.6).
     */
    private EndOutcome consentWithdrawn(UUID requestId) {
        if (ctx.status().isTerminal()) {
            return EndOutcome.NOT_ACTIVE;
        }
        blockChildRecording();
        if (ctx.status() == SessionStatus.CLOSING) {
            return EndOutcome.CLOSING;
        }
        if (START_PHASE.contains(ctx.status())) {
            failWithConsentWithdrawn();
            // 시작 단계는 202 {}(다른 전이가 먼저 이겨 종료됐어도 같은 답)
            return ctx.status() == SessionStatus.CLOSING ? EndOutcome.CLOSING : EndOutcome.ACCEPTED_EMPTY;
        }
        beginClosing(Trigger.CONSENT_WITHDRAWN, requestId);
        return ctx.status() == SessionStatus.CLOSING ? EndOutcome.CLOSING : EndOutcome.NOT_ACTIVE;
    }

    /** 시작 단계 동의 철회: FAILED(CONSENT_WITHDRAWN). START_SESSION이 나갔고 연결이 살아 있으면 CLOSE_TRANSPORT(CONSENT_WITHDRAWN). */
    private void failWithConsentWithdrawn() {
        ActivitySessionRepository.MultiOriginTransition result = ctx.services().transitions().transitionFromAny(
                ctx.plan().sessionId(), Trigger.CONSENT_WITHDRAWN_START.origins(), SessionStatus.FAILED,
                read -> TransitionTable.changeFor(Trigger.CONSENT_WITHDRAWN_START, null, ctx.services().clock().now())
                        .withTerminationRequestedAt(ctx.services().clock().now()),
                SessionTransitions.Origin.REALTIME, ctx.plan().traceId());
        if (!result.transitioned()) {
            syncFromDb();
            return;
        }
        afterFailed(CloseTransport.Reason.CONSENT_WITHDRAWN);
    }

    /**
     * BR1.7: 동의 철회를 받은 뒤의 아이 전사 · 원음은 저장하지 않는다. 원음 기록을 닫고(아이 · AI 모두) 이후 아이 전사는 버리며 버린 건수를
     * 센다(사건 payload는 건수만, BR12.1).
     */
    private void blockChildRecording() {
        if (ctx.consentWithdrawnAt() != null) {
            return;
        }
        ctx.consentWithdrawnAt(ctx.services().clock().now());
        ctx.relay().tap(null);
        AudioRecordings.Session open = ctx.recording();
        ctx.recording(null);
        if (open != null) {
            ctx.quietly(() -> open.close(ctx.consentWithdrawnAt()));
        }
        ctx.transcripts().discardOpenChildren(ctx.consentWithdrawnAt());
    }

    /**
     * WAITING_CHILD · ACTIVE → CLOSING 전이(BR1.3): 사건이 정한 사유를 CLOSING에 쓰고 승자만 마무리 순서를 시작한다(BR1.2 · BR4.6).
     *
     * @return 이 호출이 전이를 이겼으면 참(지면 DB 상태로 맞춘다)
     */
    boolean beginClosing(Trigger trigger, UUID requestId) {
        if (!trigger.accepts(ctx.status()) || trigger.target() != SessionStatus.CLOSING) {
            return false;
        }
        Instant now = ctx.services().clock().now();
        SessionStatus from = ctx.status();
        Optional<ActivitySessionRow> row = ctx.services().transitions().transition(ctx.plan().sessionId(), from,
                SessionStatus.CLOSING, TransitionTable.changeFor(trigger, null, now).withTerminationRequestedAt(now)
                        .withOutputStoppedAt(now),
                SessionTransitions.Origin.REALTIME, ctx.plan().traceId(), transitionExtras());
        if (row.isEmpty()) {
            syncFromDb(requestId);
            return false;
        }
        changeStatus(SessionStatus.CLOSING, ctx.services().clock().now());
        ctx.closingReason(row.get().endReason() != null ? row.get().endReason() : trigger.endReason());
        ctx.closingDetail(row.get().endDetail());
        enterClosing(now, requestId);
        return true;
    }

    /** 전이와 함께 쓸 값: 전이 시점에 켜진 기술 일시정지(BR7.2). */
    SessionTransitions.Extras transitionExtras() {
        return SessionTransitions.Extras.pause(idle.technicalPause());
    }

    /**
     * CLOSING 진입의 승자 몫(BR6.10 ① ② · BR6.12 ③ · BR4.5 · BR4.6).
     *
     * @param committedAt CLOSING 전이를 이긴 커밋 시각(30초 확인 시한의 시작점)
     * @param requestId END_REQUESTED 기록에 남길 끝내기 요청 id(모르면 null)
     */
    void enterClosing(Instant committedAt, UUID requestId) {
        env.stopPracticeProgress();
        Instant now = ctx.services().clock().now();
        ctx.closingAt(committedAt);
        ctx.services().recorder().putEvent(new SessionEvent(UUID.randomUUID(), ctx.plan().sessionId(), "END_REQUESTED",
                requestId == null ? null : requestId.toString(), null, committedAt));
        // BR6.10 ① · BR4.5: 서버 쪽 출력 버퍼 취소 → STOP_OUTPUT + SESSION_STATE{CLOSING} → ② 마무리 문구 1회
        ctx.bumpGeneration();
        // U11 BR3.4: 마무리는 출력을 멈추는 일이다 — 공급자 응답 취소 대신 출력 세대를 올려 만들던 응답의 남은 조각을 버린다
        env.bumpOutput(OutputGeneration.BumpReason.STOP_INTENT);
        ctx.timers().cancel(Kind.DISCONNECT);
        ctx.timers().cancel(Kind.PLAYBACK_EXPIRY);
        ctx.timers().cancel(Kind.JOIN_WINDOW);
        ctx.timers().cancel(Kind.STOP_CONFIRM);
        ctx.timers().cancel(Kind.SILENCE);
        ctx.timers().cancel(Kind.GAP_DISPLAY);
        ctx.joinPromptPlaybackId(null);
        ctx.stopConfirm().cancel();
        ctx.stopConfirmPlaybackId(null);
        ctx.segment().reset();
        ctx.transcripts().aiFinal("", false, now);
        ctx.closing().begin(closingDevice(), ctx.plan().closing() == null ? null : ctx.plan().closing().contentVersionId(),
                ctx.playback(), UUID::randomUUID, stopReasonFor(ctx.closingReason()),
                closeReasonForClosing(ctx.closingReason()));
        // U4b BR3.1: 마무리 인사도 보낸 순간 말풍선 행
        if (ctx.plan().closing() != null && ctx.playback().isPlaying()) {
            env.approvedBubble(ctx.plan().closing(), now);
        }
        Duration elapsed = Duration.between(committedAt, now);
        ctx.timers().arm(Kind.CLOSING_CONFIRM,
                ctx.services().settings().closingConfirm().minus(elapsed.isNegative() ? Duration.ZERO : elapsed));
    }

    /** BR4.6: STOP_OUTPUT 사유 — GUARDIAN_STOP · CHILD_STOP · SILENCE는 같은 값, CONSENT_WITHDRAWN은 BEFORE_CLOSING. */
    static StopOutput.Reason stopReasonFor(EndReason reason) {
        if (reason == null) {
            return StopOutput.Reason.GUARDIAN_STOP;
        }
        return switch (reason) {
            case CHILD_STOP -> StopOutput.Reason.CHILD_STOP;
            case SILENCE -> StopOutput.Reason.SILENCE;
            case CONSENT_WITHDRAWN -> StopOutput.Reason.BEFORE_CLOSING;
            default -> StopOutput.Reason.GUARDIAN_STOP;
        };
    }

    /** BR4.6: CLOSE_TRANSPORT 사유는 마무리 사유(end_reason) 값 그대로다. */
    static CloseTransport.Reason closeReasonForClosing(EndReason reason) {
        if (reason == null) {
            return CloseTransport.Reason.GUARDIAN_STOP;
        }
        return switch (reason) {
            case CHILD_STOP -> CloseTransport.Reason.CHILD_STOP;
            case SILENCE -> CloseTransport.Reason.SILENCE;
            case CONSENT_WITHDRAWN -> CloseTransport.Reason.CONSENT_WITHDRAWN;
            default -> CloseTransport.Reason.GUARDIAN_STOP;
        };
    }

    /** BR6.7 (나) ~ (라) · BR1.9: PREPARING · DEVICE_ACK · WAITING_CHILD · ACTIVE → FAILED(실패 코드 없음), CLOSING · 종료는 그대로 202 {}. */
    private EndOutcome coreAbandoned() {
        if (ctx.status() == SessionStatus.CLOSING || ctx.status().isTerminal()) {
            return EndOutcome.ACCEPTED_EMPTY;
        }
        multiOriginFail(Trigger.CORE_ABANDONED, null, SessionTransitions.Origin.CORE_ABANDONED);
        return EndOutcome.ACCEPTED_EMPTY;
    }

    void onConnectionLost(ConnectionLost lost) {
        if (!ctx.fromBoundConnection(lost.connectionId())) {
            return;
        }
        // SD-17: 연결 교체 · 끊김마다 세대를 올린다. 세션 전이는 ④ 시계(ACTIVE) · ② · ③ 시한이 정한다
        ctx.bumpGeneration();
        ctx.playback().clear();
        ctx.devicePlaying().clear();
        ctx.timers().cancel(Kind.PLAYBACK_EXPIRY);
        // U3-J18 · U4b BR6.1: 진행 중 세션의 끊김 구간을 연다(새 연결이 다시 묶이면 닫고, ABORTED로 끝나면 열린 채 남는다).
        // WAITING_CHILD · ACTIVE는 기술 일시정지도 곧바로 켠다(연결이 닫히면 점선 시계를 기다리지 않는다)
        Instant now = ctx.services().clock().now();
        if ((Trigger.DISCONNECT.accepts(ctx.status()) || ctx.status() == SessionStatus.CLOSING) && ctx.openGapId() == null) {
            ctx.openGapId(ctx.services().gaps().open(ctx.plan().sessionId(), SessionGapRepository.Kind.DISCONNECT, now));
        }
        if (Trigger.DISCONNECT.accepts(ctx.status())) {
            idle.startPause(idle.lastDeviceMessageAt(), now);
        }
    }

    /** DECLINED 마무리 인사(BR4.7 · U4b-J8): 출력 · 입력을 멈추고 CLOSING 문구 1회 → 그 끝 표식 뒤 CLOSE_TRANSPORT(DECLINED). */
    void startFarewell() {
        ctx.bumpGeneration();
        ctx.timers().cancel(Kind.SEGMENT_QUIET);
        ctx.timers().cancel(Kind.DISCONNECT);
        ctx.timers().cancel(Kind.PLAYBACK_EXPIRY);
        ctx.segment().reset();
        ctx.relay().setAccepting(false);
        ctx.joinPromptPlaybackId(null);
        boolean closedNow = ctx.closing().beginFarewell(closingDevice(),
                ctx.plan().closing() == null ? null : ctx.plan().closing().contentVersionId(), ctx.playback(), UUID::randomUUID);
        if (closedNow) {
            finishFarewell(true);
            return;
        }
        ctx.timers().arm(Kind.CLOSING_CONFIRM, ctx.services().settings().closingConfirm());
    }

    /**
     * DECLINED 인사 순서를 끝낸다(CLOSE_TRANSPORT를 보냈거나 상한을 넘김 · 같은 기기의 새 세션). 그 뒤 정리한다(정산은 인사 순서보다 앞서지 않는다,
     * BR4.7).
     *
     * @param deviceStillOurs 기기가 아직 이 세션에 묶여 있는가(아니면 기기 명령을 보내지 않는다)
     */
    void finishFarewell(boolean deviceStillOurs) {
        ctx.timers().cancel(Kind.CLOSING_CONFIRM);
        Instant now = ctx.services().clock().now();
        if (deviceStillOurs) {
            ctx.annotateQuietly(SessionStatus.DECLINED, SessionChange.none().withTransportClosedAt(now));
            ctx.sendControl(new SessionState(View.STANDBY));
        }
        afterTerminal();
    }

    /** 이 세션이 아직 기기 연결을 쥐고 있는가(같은 기기에 새 세션이 묶였으면 거짓, BR4.7). */
    boolean deviceStillOurs() {
        DeviceLink current = ctx.link();
        if (current == null || !current.isOpen()) {
            return false;
        }
        UUID bound = current.boundSessionId();
        return bound == null || bound.equals(ctx.plan().sessionId());
    }

    /** U4b: DECLINED 인사 순서가 아직 끝나지 않았는가. */
    boolean farewellPending() {
        return ctx.status() == SessionStatus.DECLINED && ctx.closing().step() == ClosingSequence.Step.AWAITING_CLOSING_MARK;
    }

    /**
     * 종료 상태로 간 뒤의 뒷일(U4b BR8.1 · WF7): ① 공급자를 닫고 provider_closed_at ② 정산 창(공급자 연결이 닫혔다는 알림 또는 record-settle) —
     * 그 동안 온 아이 확정 전사는 발화 시작이 ended_at보다 이르면 저장한다(BR8.6) ③ ~ ⑦ {@link #finishSettle}. 정산 입구가 없으면(실행기 단위 시험
     * 기본값) 곧바로 정리한다. 세션당 한 번이다.
     */
    void afterTerminal() {
        if (ctx.terminated() || ctx.settling()) {
            return;
        }
        env.stopPracticeProgress();
        if (!ctx.services().settlement().enabled()) {
            teardown();
            return;
        }
        ctx.settling(true);
        Instant now = ctx.services().clock().now();
        ctx.sessionEndedAt(now);
        try {
            ctx.sessionEndedAt(ctx.services().transitions().find(ctx.plan().sessionId()).map(ActivitySessionRow::endedAt)
                    .filter(Objects::nonNull).orElse(now));
        } catch (RuntimeException e) {
            ctx.services().events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
        }
        ctx.timers().cancelAll();
        if (env.inputGroupOpen()) {
            // 묶던 입력 전사는 정산 창 안에서 이어지는 조각을 받아 조용해지면(또는 창이 끝나면) 확정한다
            ctx.timers().arm(Kind.INPUT_TRANSCRIPT_QUIET, ctx.services().settings().inputTranscriptQuiet());
        }
        ctx.relay().setAccepting(false);
        boolean alreadyClosed = ctx.providerSession() == null || ctx.providerClosedSignal();
        closeProvider();
        SessionStatus ended = ctx.status();
        ctx.annotateQuietly(ended, SessionChange.none().withProviderClosedAt(now));
        if (alreadyClosed) {
            finishSettle();
            return;
        }
        ctx.timers().arm(Kind.RECORD_SETTLE, ctx.services().settings().lifecycle().recordSettle());
    }

    /**
     * 정산 창이 끝났다(BR8.1 ③ ~ ⑦): 확장점 정산 시점 · 전사 기록기 비우기 · practice 값 · 스냅숏 + SessionRecordCaptured 한 트랜잭션. 쓰기가 실패하면
     * 앱 오류 한 줄을 남기고 정리한다(재무장이 다시 쓴다). 이 뒤에 온 사건은 무시한다(BR8.7).
     */
    void finishSettle() {
        if (!ctx.settling() || ctx.terminated()) {
            return;
        }
        Duration remaining = env.practiceSettlementDelay();
        if (remaining.isPositive()) {
            ctx.timers().arm(Kind.RECORD_SETTLE, remaining);
            return;
        }
        ctx.timers().cancel(Kind.RECORD_SETTLE);
        ctx.quietly(env::flushInputGroupInSettle);
        Instant cutoff = ctx.services().clock().now();
        ctx.quietly(() -> ctx.transcripts().closeAll(cutoff));
        ctx.quietly(() -> ctx.services().recorder().flushSession(ctx.plan().sessionId(), ctx.services().settings().timerTick()));
        try {
            ctx.services().settlement().capture(ctx.plan().sessionId(), env.practiceRecordForSettle(cutoff), cutoff);
        } catch (RuntimeException e) {
            ctx.services().events().appError(EventLog.Component.SESSION_LIFECYCLE, e);
        }
        teardown();
    }

    // ---- 전이 보조

    Optional<ActivitySessionRow> transition(SessionStatus from, Trigger trigger, FailureCode code) {
        Optional<ActivitySessionRow> row = ctx.services().transitions().transition(ctx.plan().sessionId(), from, trigger.target(),
                TransitionTable.changeFor(trigger, code, ctx.services().clock().now()), SessionTransitions.Origin.REALTIME,
                ctx.plan().traceId());
        row.ifPresent(value -> changeStatus(value.status(), ctx.services().clock().now()));
        return row;
    }

    /** 출발 상태가 둘 이상인 → FAILED(FOR UPDATE로 읽은 상태). 이기면 CLOSE_TRANSPORT(FAILED) 규칙과 정리를 한다. */
    void multiOriginFail(Trigger trigger, FailureCode code, SessionTransitions.Origin origin) {
        ActivitySessionRepository.MultiOriginTransition result = ctx.services().transitions().transitionFromAny(
                ctx.plan().sessionId(), trigger.origins(), SessionStatus.FAILED,
                read -> TransitionTable.changeFor(trigger, code, ctx.services().clock().now()), origin, ctx.plan().traceId());
        if (!result.transitioned()) {
            syncFromDb();
            return;
        }
        changeStatus(SessionStatus.FAILED, ctx.services().clock().now());
        afterFailed();
    }

    /** BR6.8: FAILED로 갈 때 START_SESSION이 나갔고 연결이 살아 있으면 CLOSE_TRANSPORT(FAILED) + SESSION_STATE{STANDBY}. */
    void afterFailed() {
        afterFailed(CloseTransport.Reason.FAILED);
    }

    /**
     * BR6.8 · U4b BR1.5: FAILED로 갈 때 START_SESSION이 나갔고 연결이 살아 있으면 CLOSE_TRANSPORT(사유) + SESSION_STATE{STANDBY}. 시작 단계 동의
     * 철회는 사유가 CONSENT_WITHDRAWN이다.
     */
    void afterFailed(CloseTransport.Reason reason) {
        changeStatus(SessionStatus.FAILED, ctx.services().clock().now());
        ctx.bumpGeneration();
        if (ctx.startSent() && ctx.link() != null && ctx.link().isOpen()) {
            ctx.sendControl(new CloseTransport(reason));
            ctx.sendControl(new SessionState(View.STANDBY));
        }
        afterTerminal();
    }

    /** 진 전이 뒤 DB의 지금 상태로 맞춘다. 종료 상태면 정리한다. */
    void syncFromDb() {
        syncFromDb(null);
    }

    /**
     * 진 전이 뒤 DB의 지금 상태로 맞춘다. 종료 상태면 정리하고, 메모리와 다른 비종료 상태를 받아들였으면 그 상태의 시한 · 부수 효과를 한
     * 번 건다(F5 · J291).
     *
     * @param endRequestId CLOSING을 받아들일 때 END_REQUESTED에 남길 끝내기 요청 id(끝내기 처리 중이 아니면 null, J293)
     */
    void syncFromDb(UUID endRequestId) {
        Optional<ActivitySessionRow> row = ctx.services().transitions().find(ctx.plan().sessionId());
        if (row.isEmpty()) {
            teardown();
            return;
        }
        SessionStatus before = ctx.status();
        changeStatus(row.get().status(), ctx.services().clock().now());
        if (ctx.status().isTerminal()) {
            afterTerminal();
            return;
        }
        if (ctx.status() != before) {
            env.adopt(row.get(), endRequestId);
        }
    }

    ClosingSequence.Device closingDevice() {
        return new ClosingSequence.Device() {
            @Override
            public boolean stopOutput(StopOutput.Reason reason) {
                return ctx.sendControl(new StopOutput(reason));
            }

            @Override
            public boolean sessionState(View view) {
                return ctx.sendControl(new SessionState(view));
            }

            @Override
            public boolean playApproved(UUID playbackId, String contentCode, UUID contentVersionId) {
                return ctx.sendControl(new PlayApprovedContent(playbackId, contentCode, contentVersionId));
            }

            @Override
            public boolean closeTransport(CloseTransport.Reason reason) {
                return ctx.sendControl(new CloseTransport(reason));
            }
        };
    }

    /**
     * 세션 상태를 바꾸는 유일한 자리(U11 BR5.4, 첫 판 검토 R-15). 지금은 대입뿐이고 동작을 바꾸지 않는다 — 판정 턴이 {@code openedInStatus}를 벗어나는
     * 모든 전이를 이 자리에서 잡는다(PR2).
     */
    void changeStatus(SessionStatus next, Instant now) {
        ctx.status(next);
        judgment.onStatus(next);
    }

    void closeProvider() {
        ctx.providerClosing(true);
        ProviderConnector.Attempt attempt = ctx.providerAttempt();
        if (attempt != null) {
            attempt.cancel();
        }
        ProviderSession session = ctx.providerSession();
        if (session != null && session.isOpen()) {
            session.close(ctx.services().requests().sessionClose());
        }
    }

    /**
     * 종료 정리(PD-1 생명주기 3): 입력 수락 끝 · 공급자 정리 · 구간 버림 · 열린 행 닫기 · 기록기 마지막 플러시 · 원음 기록 닫기 · 레지스트리 제거.
     * 리뷰 반영 7: 단계마다 예외를 따로 받아(앱 오류 한 줄) 앞 단계가 실패해도 뒤 단계 — 특히 원음 파일 핸들과 메타의 ended_at — 를 마친다.
     * 리뷰 반영 4: 처리하지 못하고 남은 연결 교체(LinkReplaced)는 그 새 연결을 대기로 돌려보낸다.
     */
    void teardown() {
        if (ctx.terminated()) {
            return;
        }
        ctx.terminated(true);
        ctx.outputGeneration().incrementAndGet();
        Instant now = ctx.services().clock().now();
        try {
            ctx.quietly(ctx.timers()::cancelAll);
            ctx.quietly(ctx.sendQueue()::shutdown);
            ctx.quietly(env::cancelPracticeTimers);
            ctx.quietly(judgment::shutdown);
            // U13 호출 지점 ④: 진행 중인 제브 요청을 취소한다(보관 행은 아래 closeAll이 닫는다, BR4.5)
            ctx.quietly(guardrail::onExit);
            ctx.quietly(ctx.relay()::stop);
            ctx.quietly(this::closeProvider);
            ctx.quietly(() -> {
                ProviderSession session = ctx.providerSession();
                if (session != null) {
                    session.close();
                }
            });
            ctx.quietly(ctx.segment()::reset);
            ctx.quietly(ctx.playback()::clear);
            ctx.quietly(() -> ctx.transcripts().closeAll(now));
            ctx.quietly(() -> ctx.services().latency().sessionEnded(ctx.plan().sessionId()));
            ctx.quietly(() -> ctx.services().recorder().finish(ctx.plan().sessionId()));
            ctx.quietly(() -> ctx.unbind(ctx.link()));
            ctx.pendingCommands().clear();
            ctx.quietly(() -> {
                if (ctx.recording() != null) {
                    ctx.recording().close(now);
                }
            });
        } finally {
            for (Message left : ctx.mailbox().closeAndRelease()) {
                if (left instanceof LinkReplaced replaced) {
                    ctx.quietly(() -> standDown(replaced.link(), ctx.plan().sessionId(), ctx.status(), replaced.resumed()));
                }
            }
            onTerminated.run();
        }
    }
}
